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
        // Crash Dump Interceptor
        Thread::class
            .java
            .declaredMethods
            .firstOrNull { it.name == "dispatchUncaughtException" }
            ?.let { AevixHookBuilder(it).intercept(CrashDumpHooker) }

        // Process-specific Interceptors
        if (isSystem) {
            val zygoteInitClass = Class.forName("com.android.internal.os.ZygoteInit")
            zygoteInitClass.declaredMethods
                .filter { it.name == "handleSystemServerProcess" }
                .forEach { AevixHookBuilder(it).intercept(HandleSystemServerProcessHooker) }
        } else {
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

        // Application Load Interceptors
        val loadedApkClass = Class.forName("android.app.LoadedApk")
        loadedApkClass.declaredConstructors.forEach {
            // Hook all constructors of LoadedApk to catch early instantiations securely
            AevixHookBuilder(it).intercept(LoadedApkCtorHooker)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            loadedApkClass.declaredMethods
                .filter { it.name == "createAppFactory" }
                .forEach { AevixHookBuilder(it).intercept(LoadedApkCreateAppFactoryHooker) }
        }

        loadedApkClass.declaredMethods
            .filter { it.name == "createOrUpdateClassLoaderLocked" }
            .forEach { AevixHookBuilder(it).intercept(LoadedApkCreateCLHooker) }

        // ActivityThread Attachment Interceptor
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        activityThreadClass.declaredMethods
            .filter { it.name == "attach" }
            .forEach { AevixHookBuilder(it).intercept(AppAttachHooker) }

        // Late System Server Injection
        if (systemServerStarted) {
            val activityService: IBinder? = android.os.ServiceManager.getService("activity")
            if (activityService != null) {
                val classLoader = activityService.javaClass.classLoader
                if (classLoader != null) {
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

                    // Say plainly what this is. onSystemServerStarting is documented as "system
                    // server is ready to start critical services", which is no longer true here -
                    // the services are already running. Modules that key off it should treat a
                    // late dispatch as best effort.
                    Utils.logW(
                        "Late system server injection: dispatching onSystemServerStarting after " +
                            "system server has already started; critical services are live"
                    )
                    StartBootstrapServicesHooker.dispatchSystemServerLoaded(classLoader)
                }
            }
        }
    }
}
