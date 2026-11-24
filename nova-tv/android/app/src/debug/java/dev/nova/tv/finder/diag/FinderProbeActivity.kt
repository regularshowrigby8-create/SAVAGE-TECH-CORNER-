package dev.nova.tv.finder.diag

import android.app.Activity
import android.os.Bundle
import android.util.Log
import dev.nova.tv.NovaApplication
import dev.nova.tv.finder.pipeline.PipelineReport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-build-only, adb-launched finder probe.
 *
 * The normal UI hides the reason a round found nothing behind a toast. This
 * activity runs the exact production runners and the exact UI-button methods,
 * then writes explicit JSON and the full FinderTrace into app-private files.
 * The CI emulator script pulls those files with `run-as`. It is not shipped in
 * release builds because it lives under src/debug.
 *
 * Launch examples:
 *   adb shell am start -n dev.nova.mobile/dev.nova.tv.finder.diag.FinderProbeActivity --es mode pipeline
 *   adb shell am start -n dev.nova.mobile/dev.nova.tv.finder.diag.FinderProbeActivity --es mode ui
 */
class FinderProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra("mode").orEmpty().ifBlank { "pipeline" }
        val tag = "NovaFinderDebug"
        FinderTrace.enabled(true)
        FinderTrace.clear()
        val started = System.currentTimeMillis()
        val app = application as NovaApplication
        Log.i(tag, "START mode=$mode browser=${app.finderBrowser.available}")

        app.appScope.launch(Dispatchers.Main.immediate) {
            val payload = JSONObject()
            var succeeded = false
            try {
                val result = withContext(Dispatchers.IO) { runMode(app, mode) }
                succeeded = result.optBoolean("ok", false)
                payload.put("ok", succeeded)
                payload.put("mode", mode)
                payload.put("result", result)
            } catch (error: Exception) {
                payload.put("ok", false)
                payload.put("mode", mode)
                payload.put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                payload.put("stack", Log.getStackTraceString(error))
                Log.e(tag, "mode=$mode threw", error)
            }
            payload.put("durationMs", System.currentTimeMillis() - started)
            payload.put("trace", FinderTrace.dump())
            try {
                withContext(Dispatchers.IO) { writeEvidence(mode, payload) }
            } catch (error: Exception) {
                Log.e(tag, "could not write evidence for mode=$mode", error)
                payload.put("evidenceWriteError", "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
            for (line in FinderTrace.snapshot()) Log.d(tag, line)
            Log.i(tag, "DONE mode=$mode ok=$succeeded durationMs=${payload.optLong("durationMs")}")
            finish()
        }
    }

    private suspend fun runMode(app: NovaApplication, mode: String): JSONObject {
        val before = counts(app)
        val result = JSONObject()
        var uiAdded = 0
        result.put("browserAvailable", app.finderBrowser.available)
        result.put("before", countsJson(before))
        when (mode) {
            "pipeline" -> {
                val website = app.serverPipelineRunner.run(0, 1, 20)
                val addon = app.addonPipelineRunner.run(0, 1, 20)
                result.put("websiteReport", reportJson(website))
                result.put("addonReport", reportJson(addon))
            }
            "ui" -> {
                val websitesAdded = app.engine.fillWebsitePoolOnce()
                val addonsAdded = app.engine.fillPool()
                uiAdded = websitesAdded + addonsAdded
                result.put("websitesAdded", websitesAdded)
                result.put("addonsAdded", addonsAdded)
                result.put("websiteRejections", app.engine.lastServerRejections.summary)
                result.put("addonRejections", app.engine.lastAddonRejections.summary)
            }
            else -> {
                result.put("error", "unknown-mode:$mode")
            }
        }
        val after = counts(app)
        result.put("after", countsJson(after))
        val newPipelineSites = after.serverFinderAlive - before.serverFinderAlive +
            after.addonFinderAlive - before.addonFinderAlive
        val newUiSites = after.websitePoolSites - before.websitePoolSites +
            after.serverPoolServers - before.serverPoolServers
        result.put("newPipelineSites", newPipelineSites)
        result.put("newUiSites", newUiSites)
        val found = when (mode) {
            "pipeline" -> newPipelineSites > 0
            "ui" -> newUiSites > 0 && uiAdded > 0
            else -> false
        }
        result.put("uiButtonAdditions", uiAdded)
        result.put("foundAnything", found)
        result.put("websitePoolSample", sampleWebsites(app))
        result.put("serverPoolSample", sampleServers(app))
        result.put("ok", !result.has("error") && found)
        if (!found) {
            val why = when (mode) {
                "ui" -> "ui-buttons-added-no-sites:newPipeline=$newPipelineSites,newUi=$newUiSites,uiAdded=$uiAdded"
                else -> "no-new-verified-sites:newPipeline=$newPipelineSites,newUi=$newUiSites"
            }
            result.put("error", why)
        }
        return result
    }

    private data class Counts(
        val serverFinderAlive: Int,
        val addonFinderAlive: Int,
        val websitePoolSites: Int,
        val serverPoolServers: Int
    )

    private fun counts(app: NovaApplication): Counts = Counts(
        serverFinderAlive = app.serverFinderStore.count("alive"),
        addonFinderAlive = app.addonFinderStore.count("alive"),
        websitePoolSites = app.websitePool.state.value.size,
        serverPoolServers = app.serverPool.state.value.size
    )

    private fun countsJson(value: Counts): JSONObject = JSONObject()
        .put("serverFinderAlive", value.serverFinderAlive)
        .put("addonFinderAlive", value.addonFinderAlive)
        .put("websitePoolSites", value.websitePoolSites)
        .put("serverPoolServers", value.serverPoolServers)

    private fun reportJson(report: PipelineReport): JSONObject {
        val stageCounts = JSONObject()
        for ((key, value) in report.stageCounts) stageCounts.put(key, value)
        val rejections = JSONArray()
        for (rejection in report.rejections.take(40)) {
            rejections.put(
                JSONObject()
                    .put("stage", rejection.stage)
                    .put("url", rejection.url)
                    .put("reason", rejection.reason)
            )
        }
        val errors = JSONArray()
        for (error in report.errors) errors.put(error)
        return JSONObject()
            .put("marker", report.marker)
            .put("kind", report.kind.name)
            .put("engineCount", report.engineCount)
            .put("stageCount", report.stageCount)
            .put("target", report.target)
            .put("verifiedCount", report.verifiedCount)
            .put("targetMet", report.targetMet)
            .put("status", report.status)
            .put("ok", report.ok)
            .put("budgetStopped", report.budgetStopped)
            .put("stageCounts", stageCounts)
            .put("rejections", rejections)
            .put("errors", errors)
    }

    private fun sampleWebsites(app: NovaApplication): JSONArray {
        val out = JSONArray()
        for (record in app.serverFinderStore.alive().take(10)) {
            out.put(
                JSONObject()
                    .put("host", record.host)
                    .put("url", record.toSiteUrl())
                    .put("catalogue", record.catalogue)
                    .put("sampleUrl", record.sampleUrl)
            )
        }
        return out
    }

    private fun sampleServers(app: NovaApplication): JSONArray {
        val out = JSONArray()
        for (record in app.addonFinderStore.alive().take(10)) {
            out.put(
                JSONObject()
                    .put("host", record.host)
                    .put("baseUrl", record.baseUrl)
                    .put("manifestUrl", record.manifestUrl)
                    .put("catalogue", record.catalogue)
            )
        }
        return out
    }

    private fun writeEvidence(mode: String, payload: JSONObject) {
        val dir = File(filesDir, "finder-debug")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create ${dir.absolutePath}")
        }
        File(dir, "$mode.json").writeText(payload.toString(2))
        File(dir, "$mode.trace.txt").writeText(payload.optString("trace"))
        File(dir, "$mode.done").writeText("${System.currentTimeMillis()}\n")
    }
}
