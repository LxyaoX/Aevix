package org.aevix.core

import android.os.IBinder
import android.os.Process
import org.aevix.ipc.IFrameworkService
import org.aevix.util.Log
import org.aevix.util.Utils
import org.aevix.BuildConfig
import org.aevix.GrapheneDclHooker
import org.aevix.ParasiticManagerHooker
import org.aevix.ParasiticManagerSystemHooker
import org.aevix.Startup
import org.aevix.impl.core.AevixServiceClient

/** Main entry point for the Java-side loader, invoked via JNI from the Aevix Zygisk module. */
object Main {

    /**
     * Shared initialization logic for both System Server and Application processes.
     *
     * @param isSystem True if this is the system_server process.
     * @param isLateInject True if Zygisk injected us into a system_server that was already
     *   running, which is past the points the hooks would have caught, so the startup they drive
     *   is run by hand instead.
     * @param niceName The process name (e.g., package name or "system").
     * @param appDir The application's data directory.
     * @param binder The Binder token associated with the application service.
     */
    @JvmStatic
    fun forkCommon(
        isSystem: Boolean,
        isLateInject: Boolean,
        niceName: String,
        appDir: String?,
        binder: IBinder,
    ) {
        // Silent before anything else can speak. A release build says nothing unless asked to, and
        // every debug and verbose line this process writes lands in the host app's own log buffer,
        // where an integrity check reads it. Set here rather than after startup because startup is
        // already chatty, and because the daemon's own answer arrives too late to cover it.
        Log.muted = !BuildConfig.DEBUG

        // Initialize system-specific resolution hooks if in system_server
        if (isSystem) {
            // Both are best-effort, and both run on the system server's main thread. Contained
            // individually so a ROM that answers unexpectedly costs one of them rather than the
            // initialization below, which is what makes the framework work at all.
            runCatching { ParasiticManagerSystemHooker.start() }
                .onFailure { Utils.logE("Parasitic manager hooks failed", it) }
            // Exempt the manager host from GrapheneOS DCL restriction; no-op on other systems.
            runCatching { GrapheneDclHooker.start() }
                .onFailure { Utils.logE("GrapheneOS DCL exemption failed", it) }
        }

        // Initialize Xposed bridge components
        val appService = IFrameworkService.Stub.asInterface(binder)
        runCatching { Startup.initXposed(isSystem, niceName, appDir, appService) }
            .onFailure { Utils.logE("Framework initialization failed for $niceName", it) }

        // Only the daemon may lift the silence set at the top of this method, and only by actually
        // answering: a connection that is not up yet is not an answer, and the old default -- log
        // everything unless told otherwise -- turned every failed connection into one.
        runCatching { AevixServiceClient.isLogMutedOrNull() }
            .getOrNull()
            ?.let { muted -> Log.muted = muted }

        // Check if this process is the designated Aevix Manager.
        if (niceName == BuildConfig.ManagerPackageName) {
            ParasiticManagerHooker.isParasitic = Process.myUid() == BuildConfig.HostPackageUid
            val type = if (ParasiticManagerHooker.isParasitic) "parasitic" else "user-installed"
            val started =
                runCatching { ParasiticManagerHooker.start() }
                    .onFailure { Utils.logE("Parasitic manager start failed", it) }
                    .getOrDefault(false)
            if (started) {
                Utils.logI("Manager ($type) loaded into host, skipping standard bootstrap.")
                return
            }
        }

        // Standard Xposed module loading for third-party apps
        Utils.logV("Starting for $niceName (UID: ${Process.myUid()})")
        Startup.bootstrapXposed(isSystem && isLateInject)
    }
}
