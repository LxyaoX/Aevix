package org.aevix.impl.core

import android.app.ActivityThread
import android.os.Build
import android.os.IBinder
import dalvik.system.DexFile
import org.aevix.util.Utils
import org.aevix.ipc.IFrameworkService
import org.aevix.impl.di.AevixBootstrap
import org.aevix.impl.hookers.*
import org.aevix.impl.hooks.AevixHookBuilder

/**
 * Modern framework initialization and bootstrap sequence. Deploys interceptors into the ART runtime
 * and handles early process deoptimization.
 */
object AevixStartup {

    @JvmStatic
    fun init(
        isSystem: Boolean,
        processName: String?,
        appDir: String?,
        service: IFrameworkService?,
    ) {
        AevixServiceClient.init(service, processName ?: "android")
        AevixDeopter.deoptBootMethods()
    }

    /**
     * Registers a pre-built [LoadedApk] whose constructor ran before the hooks were installed -- the
     * LSPatch rootless target being the one case. Once tracked, [LoadedApkCreateAppFactoryHooker] and
     * [LoadedApkCreateCLHooker] dispatch its `onPackageLoaded`/`onPackageReady` (and legacy
     * `handleLoadPackage`) exactly once when its class loader is realized, with `onPackageLoaded`
     * running *before* the app's `AppComponentFactory` static initializer.
     */
    @JvmStatic
    fun trackLoadedApk(loadedApk: Any) {
        LoadedApkTracker.activeApks.add(loadedApk)
    }

    @JvmStatic
    fun bootstrap(isSystem: Boolean, systemServerStarted: Boolean) {
        // Each stage is contained on its own. They are independent — a ROM that cannot be hooked
        // on one of them is not a reason to skip the rest, and this runs in every process, so the
        // failure that used to abort the whole sequence here left the process with no hooks at all
        // and nothing in the log saying which one it was.

        // Crash Dump Interceptor
        runCatching {
                Thread::class
                    .java
                    .declaredMethods
                    .firstOrNull { it.name == "dispatchUncaughtException" }
                    ?.let { AevixHookBuilder(it).intercept(CrashDumpHooker) }
            }
            .onFailure { Utils.logE("Hook on Thread.dispatchUncaughtException failed", it) }

        // Process-specific Interceptors
        if (isSystem) {
            runCatching {
                    val zygoteInitClass = Class.forName("com.android.internal.os.ZygoteInit")
                    zygoteInitClass.declaredMethods
                        .filter { it.name == "handleSystemServerProcess" }
                        .forEach {
                            AevixHookBuilder(it).intercept(HandleSystemServerProcessHooker)
                        }
                }
                .onFailure {
                    Utils.logE("Hook on ZygoteInit.handleSystemServerProcess failed", it)
                }
        } else {
            runCatching {
                    DexFile::class
                        .java
                        .declaredMethods
                        .filter {
                            it.name == "openDexFile" ||
                                it.name == "openInMemoryDexFile" ||
                                it.name == "openInMemoryDexFiles"
                        }
                        .forEach { AevixHookBuilder(it).intercept(DexTrustHooker) }
                }
                .onFailure { Utils.logE("Hooks on DexFile failed", it) }
        }

        // Application Load Interceptors
        val loadedApkClass =
            runCatching { Class.forName("android.app.LoadedApk") }
                .onFailure { Utils.logE("android.app.LoadedApk is missing", it) }
                .getOrNull()

        if (loadedApkClass != null) {
            runCatching {
                    loadedApkClass.declaredConstructors.forEach {
                        // Hook all constructors of LoadedApk to catch early instantiations securely
                        AevixHookBuilder(it).intercept(LoadedApkCtorHooker)
                    }
                }
                .onFailure { Utils.logE("Hooks on LoadedApk constructors failed", it) }

            runCatching {
                    loadedApkClass.declaredMethods
                        .filter { it.name == "createAppFactory" }
                        .forEach { AevixHookBuilder(it).intercept(LoadedApkCreateAppFactoryHooker) }
                }
                .onFailure { Utils.logE("Hooks on LoadedApk.createAppFactory failed", it) }

            runCatching {
                    loadedApkClass.declaredMethods
                        .filter { it.name == "createOrUpdateClassLoaderLocked" }
                        .forEach { AevixHookBuilder(it).intercept(LoadedApkCreateCLHooker) }
                }
                .onFailure {
                    Utils.logE("Hooks on LoadedApk.createOrUpdateClassLoaderLocked failed", it)
                }
        }

        // ActivityThread Attachment Interceptor
        runCatching {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                activityThreadClass.declaredMethods
                    .filter { it.name == "attach" }
                    .forEach { AevixHookBuilder(it).intercept(AppAttachHooker) }
            }
            .onFailure { Utils.logE("Hooks on ActivityThread.attach failed", it) }

        // Late System Server Injection
        if (systemServerStarted) {
            runCatching { lateSystemServerInjection() }
                .onFailure { Utils.logE("Late system server injection failed", it) }
        }
    }

    /**
     * The routines the hooks would have driven, run by hand because this process was injected into
     * a system server that was already past them.
     *
     * Split from [bootstrap] so a failure in here — an ActivityThread that cannot be reached yet is
     * the likely one — does not cost the hooks installed above it.
     */
    private fun lateSystemServerInjection() {
        val activityService: IBinder? = android.os.ServiceManager.getService("activity")
        val classLoader = activityService?.javaClass?.classLoader ?: return

        // Manually trigger the routines that the hooks normally would
        HandleSystemServerProcessHooker.initSystemServer(classLoader, isLate = true)

        // ActivityThread.attach ran before we were injected, so AppAttachHooker will
        // never fire here and nothing else instantiates modules. Without this,
        // dispatchSystemServerLoaded below would notify an empty module set.
        val activityThread = ActivityThread.currentActivityThread()
        if (activityThread != null) {
            AevixBootstrap.withLegacy { it.loadModules(activityThread) }
        } else {
            Utils.logW(
                "Late system server injection: no current ActivityThread, modules cannot be loaded"
            )
        }

        // Say plainly what this is. onSystemServerStarting is documented as "system server is
        // ready to start critical services", which is no longer true here - the services are
        // already running. Modules that key off it should treat a late dispatch as best effort.
        Utils.logW(
            "Late system server injection: dispatching onSystemServerStarting after " +
                "system server has already started; critical services are live"
        )
        StartBootstrapServicesHooker.dispatchSystemServerLoaded(classLoader)
    }
}
