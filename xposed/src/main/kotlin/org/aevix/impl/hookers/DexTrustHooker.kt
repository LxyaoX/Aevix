package org.aevix.impl.hookers

import android.os.Build
import io.github.libxposed.api.XposedInterface
import org.aevix.nativebridge.HookBridge
import org.aevix.util.Utils

/**
 * Intercepts DEX file parsing to dynamically mark our framework's ClassLoader as trusted. Prevents
 * ART from blocking reflective access by the hooking engine.
 *
 * This fires for every `DexFile.openDexFile*` call in the process, including the ones zygote makes
 * while it is still preloading, so a throw here is a process that never finishes starting. The
 * marking is best-effort by nature — a missed one costs reflective access, not correctness — so it
 * is contained.
 */
object DexTrustHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()

        try {
            // Found by scanning rather than by `filterIsInstance`, which allocates a list on a path
            // that runs once per DEX the process opens.
            var classLoader: ClassLoader? = null
            for (arg in chain.args) {
                if (arg is ClassLoader) {
                    classLoader = arg
                    break
                }
            }
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && classLoader == null) {
                classLoader = DexTrustHooker::class.java.classLoader
            }

            while (classLoader != null) {
                if (classLoader === DexTrustHooker::class.java.classLoader) {
                    // Inform the native bridge that this DEX cookie is safe
                    HookBridge.setTrusted(result)
                    break
                }
                classLoader = classLoader.parent
            }
        } catch (t: Throwable) {
            Utils.logD("Marking a DEX as trusted failed; reflective access may be refused", t)
        }

        return result
    }
}
