package dev.nova.tv.finder.diag

import android.app.Activity
import android.os.Bundle
import android.util.Log
import dev.nova.tv.NovaApplication
import dev.nova.tv.finder.pipeline.PIPELINE_BUILD_MARKER
import dev.nova.tv.finder.pipeline.PipelineReport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Outer ceiling: even a non-cancellable native stall must produce a .done file. */
private const val PROBE_TOTAL_TIMEOUT_MS = 480_000L
/** Per-runner ceiling for one bounded production pipeline path. */
private const val PROBE_RUNNER_TIMEOUT_MS = 240_000L

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
        heartbeat(mode, "activity-created")

        app.appScope.launch(Dispatchers.Main.immediate) {
            val payload = JSONObject()
            var succeeded = false
            try {
                val result = withTimeoutOrNull(PROBE_TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { runMode(app, mode) }
                } ?: JSONObject()
                    .put("ok", false)
                    .put("mode", mode)
                    .put("error", "probe-run-timeout")
                succeeded = result.optBoolean("ok", false)
                payload.put("ok", succeeded)
                payload.put("mode", mode)
                payload.put("result", result)
            } catch (error: Exception) {
                heartbeat(mode, "run-threw")
                payload.put("ok", false)
                payload.put("mode", mode)
                payload.put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                payload.put("stack", Log.getStackTraceString(error))
                Log.e(tag, "mode=$mode threw", error)
            }
            payload.put("durationMs", System.currentTimeMillis() - started)
            payload.put("trace", FinderTrace.dump())
            heartbeat(mode, "writing-evidence")
            try {
                withContext(Dispatchers.IO) { writeEvidence(mode, payload) }
                heartbeat(mode, "evidence-written")
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
            "pipeline" -> coroutineScope {
                // Both production paths run concurrently: the website path uses
                // the serialised WebView renderer, while the add-on path is
                // JSON-only. One stalled path must not starve the other.
                val website = async {
                    pipelineReport(mode, "website-runner") {
                        app.serverPipelineRunner.run(0, 1, 20)
                    }
                }
                val addon = async {
                    pipelineReport(mode, "addon-runner") {
                        app.addonPipelineRunner.run(0, 1, 20)
                    }
                }
                result.put("websiteReport", website.await())
                result.put("addonReport", addon.await())
            }
            "ui" -> {
                heartbeat(mode, "ui-website-start")
                val websites = withTimeoutOrNull(PROBE_RUNNER_TIMEOUT_MS) {
                    app.engine.fillWebsitePoolOnce()
                }
                heartbeat(mode, "ui-addons-start")
                val addons = withTimeoutOrNull(PROBE_RUNNER_TIMEOUT_MS) {
                    app.engine.fillPool()
                }
                heartbeat(mode, "ui-methods-done")
                val websitesAdded = websites ?: 0
                val addonsAdded = addons ?: 0
                uiAdded = websitesAdded + addonsAdded
                if (websites == null || addons == null) {
                    result.put("error", "ui-methods-timed-out:website=${websites != null},addons=${addons != null}")
                }
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

    /** Run one bounded production pipeline path and capture a timeout as evidence. */
    private suspend fun pipelineReport(
        mode: String,
        label: String,
        block: suspend () -> PipelineReport
    ): JSONObject {
        heartbeat(mode, "$label-start")
        return try {
            val report = withTimeoutOrNull(PROBE_RUNNER_TIMEOUT_MS) { block() }
            if (report == null) {
                heartbeat(mode, "$label-timeout")
                JSONObject()
                    .put("marker", PIPELINE_BUILD_MARKER)
                    .put("status", "BUDGET_STOPPED")
                    .put("stageCounts", JSONObject())
                    .put("errors", JSONArray().put("$label-timeout"))
            } else {
                heartbeat(mode, "$label-done")
                reportJson(report)
            }
        } catch (error: Exception) {
            heartbeat(mode, "$label-threw")
            Log.e("NovaFinderDebug", "$label threw", error)
            JSONObject()
                .put("marker", PIPELINE_BUILD_MARKER)
                .put("status", "DEGRADED")
                .put("stageCounts", JSONObject())
                .put("errors", JSONArray().put("${error.javaClass.simpleName}: ${error.message.orEmpty()}"))
                .put("stack", Log.getStackTraceString(error))
        }
    }

    /** A tiny app-private progress file survives a stalled activity or crash. */
    private fun heartbeat(mode: String, stage: String) {
        try {
            val dir = File(filesDir, "finder-debug")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e("NovaFinderDebug", "cannot create heartbeat dir ${dir.absolutePath}")
                return
            }
            File(dir, "$mode.heartbeat.txt").writeText("$stage ${System.currentTimeMillis()}\n")
            Log.i("NovaFinderDebug", "HEARTBEAT mode=$mode stage=$stage")
        } catch (error: Exception) {
            Log.e("NovaFinderDebug", "could not write heartbeat mode=$mode stage=$stage", error)
        }
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
