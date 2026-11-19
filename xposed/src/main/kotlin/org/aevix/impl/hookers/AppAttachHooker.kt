package org.aevix.impl.hookers

import io.github.libxposed.api.XposedInterface
import org.aevix.impl.di.AevixBootstrap
import org.aevix.util.Utils

/**
 * Intercepts the early ApplicationThread attachment phase. Triggers the legacy compatibility layer
 * to load modern modules into the process.
 *
 * `ActivityThread.attach` also runs in system_server, where it is part of
 * `SystemServer.startOtherServices`, so a module that throws out of `loadModules` would take the
 * rest of that method with it. The call is contained here: what a module does wrong is its own
 * business, but it is not allowed to end the process that hosts it.
 */
object AppAttachHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        // Execute the actual attach() method first
        val result = chain.proceed()

        // Delegate legacy module loading via DI
        val activityThread = chain.thisObject
        if (activityThread != null) {
            try {
                AevixBootstrap.withLegacy { delegate -> delegate.loadModules(activityThread) }
            } catch (t: Throwable) {
                Utils.logE("Module loading on ActivityThread.attach failed", t)
            }
        }

        return result
    }
}
