package dev.nova.tv

import android.app.Application
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph.
 *
 * Deliberately not Hilt: the graph is small enough that a single object is easier
 * to read, and an Android TV app has enough startup cost without codegen.
 */
class NovaApplication : Application() {

    /** Survives every screen; cancelled only when the process dies. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: HttpClient by lazy { HttpClient() }
    val addonClient: AddonClient by lazy { AddonClient(http) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: NovaApplication
            private set
    }
}
