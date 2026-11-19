package org.aevix.impl.hookers

import io.github.libxposed.api.XposedInterface
import org.aevix.impl.AevixLifecycleManager
import org.aevix.impl.core.AevixDeopter
import org.aevix.impl.di.AevixBootstrap
import org.aevix.impl.hooks.AevixHookBuilder
import org.aevix.util.Utils

/**
 * Intercepts the system server initialization process to deoptimize targets and attach bootstrap
 * hookers dynamically.
 *
 * Everything this does runs on the system server's own startup path, inside
 * `ZygoteInit.handleSystemServerProcess`. An exception escaping [intercept] therefore does not
 * abort a hook; it aborts the system server, and the framework restarts — which reads on the device
 * as an unexplained soft reboot at boot. So the interceptor contains itself, and a step that cannot
 * complete costs that step rather than the process.
 */
object HandleSystemServerProcessHooker : XposedInterface.Hooker {

    interface Callback {
        fun onSystemServerLoaded(classLoader: ClassLoader)
    }

    @Volatile var callback: Callback? = null

    @Volatile
    var systemServerCL: ClassLoader? = null
        private set

    // Set before the first step runs, so a second entry cannot start a second round while the
    // first is still going. `systemServerCL` is assigned only once everything that can be done has
    // been, because it is the flag callers read to mean "the work is done".
    @Volatile private var initStarted = false

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        try {
            val classLoader = Thread.currentThread().contextClassLoader
            if (classLoader != null) {
                initSystemServer(classLoader)
            }
        } catch (t: Throwable) {
            Utils.logE("System server initialization failed; it continues without Aevix", t)
        }
        return result
    }

    /** Performs system server initialization. */
    @Synchronized
    fun initSystemServer(classLoader: ClassLoader, isLate: Boolean = false) {
        if (systemServerCL != null || initStarted) return
        initStarted = true

        // Deoptimize heavily inlined system server paths
        runCatching { AevixDeopter.deoptSystemServerMethods(classLoader) }
            .onFailure { Utils.logE("Deoptimizing system server methods failed", it) }

        if (!isLate) {
            // Dynamically locate and hook the bootstrap service initializer. A ROM whose
            // SystemServer has no `startBootstrapServices` cannot be dispatched into, but that is
            // a module that never loads — not a system server that never starts.
            runCatching {
                    val sysServerClass =
                        Class.forName("com.android.server.SystemServer", false, classLoader)
                    val startMethod =
                        sysServerClass.declaredMethods.find { it.name == "startBootstrapServices" }
                            ?: throw NoSuchMethodException(
                                "com.android.server.SystemServer.startBootstrapServices not found"
                            )

                    // Ensure we can hook the private method
                    startMethod.isAccessible = true
                    AevixHookBuilder(startMethod).intercept(StartBootstrapServicesHooker)
                }
                .onFailure {
                    Utils.logE(
                        "Could not hook SystemServer.startBootstrapServices; modules will not be " +
                            "dispatched into system_server",
                        it,
                    )
                }
        }

        systemServerCL = classLoader
        runCatching { callback?.onSystemServerLoaded(classLoader) }
            .onFailure { Utils.logE("System server loaded callback failed", it) }
    }
}

/** Dispatches the core system server bootstrap event to the module engines. */
object StartBootstrapServicesHooker : XposedInterface.Hooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        // Contained for the same reason as the interceptor above: this runs inside
        // `SystemServer.startBootstrapServices`, and a throw here stops the services coming up.
        try {
            HandleSystemServerProcessHooker.systemServerCL?.let { dispatchSystemServerLoaded(it) }
        } catch (t: Throwable) {
            Utils.logE("Dispatching the system server bootstrap event failed", t)
        }
        return chain.proceed()
    }

    /** Dispatches module loading events. */
    fun dispatchSystemServerLoaded(classLoader: ClassLoader) {
        // Dispatch to modern framework modules
        runCatching { AevixLifecycleManager.dispatchSystemServerStarting(classLoader) }
            .onFailure { Utils.logE("Modern module dispatch into system_server failed", it) }

        // Dispatch to legacy framework modules
        runCatching {
                AevixBootstrap.withLegacy { delegate -> delegate.onSystemServerLoaded(classLoader) }
            }
            .onFailure { Utils.logE("Legacy module dispatch into system_server failed", it) }
    }
}
