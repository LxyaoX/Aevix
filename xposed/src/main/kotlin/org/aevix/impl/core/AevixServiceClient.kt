package org.aevix.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.aevix.ipc.LoadedModule
import org.aevix.ipc.IProcessChannel
import org.aevix.ipc.IFrameworkService
import org.aevix.util.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object AevixServiceClient : IFrameworkService, IBinder.DeathRecipient {

    private const val TAG = "AevixServiceClient"

    private var service: IFrameworkService? = null
    var processName: String = ""
        private set

    @Synchronized
    fun init(appService: IFrameworkService?, niceName: String) {
        val binder = appService?.asBinder()
        if (service == null && binder != null) {
            runCatching {
                    service = appService
                    processName = niceName
                    binder.linkToDeath(this, 0)
                }
                .onFailure {
                    Log.e(TAG, "Failed to link to death for service in process: $niceName", it)
                    service = null
                }

            // Handed over here rather than after module loading, and carrying no module identity:
            // system_server loads its modules before the daemon's module cache exists, so anything
            // that had to name a module here could not work for it.
            service?.let {
                try {
                    it.attachProcessChannel(AevixProcessChannel)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to attach the process channel in process: $niceName", t)
                }
            }
        }
    }

    override fun attachProcessChannel(channel: IProcessChannel?) {
        try {
            service?.attachProcessChannel(channel)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach the process channel", t)
        }
    }

    override fun isLogMuted(): Boolean {
        return isLogMutedOrNull() ?: false
    }

    /**
     * The daemon's answer, or null when there is no daemon to ask.
     *
     * An injected process starts silent and only a real answer may unmute it; a connection that is
     * not up yet is not an answer. Collapsing the two into `false`, as the interface method has to,
     * reads as "log everything" and is what put the framework's own lines into every host app's
     * log buffer on a release build.
     */
    fun isLogMutedOrNull(): Boolean? {
        return runCatching { service?.isLogMuted }.getOrNull()
    }

    override fun getLegacyModules(): List<LoadedModule> {
        return runCatching { service?.legacyModules }.getOrNull() ?: emptyList()
    }

    override fun getModules(): List<LoadedModule> {
        return runCatching { service?.modules }.getOrNull() ?: emptyList()
    }

    override fun getPrefsPath(packageName: String): String? {
        return runCatching { service?.getPrefsPath(packageName) }.getOrNull()
    }

    override fun openManagerApk(): ParcelFileDescriptor? {
        return runCatching { service?.openManagerApk() }.getOrNull()
    }

    override fun requestManagerService(): IBinder? {
        return runCatching { service?.requestManagerService() }.getOrNull()
    }

    override fun asBinder(): IBinder? {
        return service?.asBinder()
    }

    override fun binderDied() {
        service?.asBinder()?.unlinkToDeath(this, 0)
        service = null
    }
}
