package org.aevix.daemon.data

import java.nio.file.Path
import org.aevix.daemon.BuildConfig
import org.aevix.ipc.LoadedModule

data class ProcessScope(val processName: String, val uid: Int)

/**
 * An immutable snapshot of the Daemon's state. Any updates will generate a new copy of this class
 * and atomically swap the reference.
 */
data class DaemonState(
    // State non configurable for users.
    //
    // Two switches, because the two halves of "hiding" cost different things.
    //
    // This one is the framework's own names. On, a DEX handed to a process has its `org/aevix/*`
    // entry classes renamed to names drawn fresh on every boot, so a scan for a hook framework's
    // classes finds nothing to name. A module compiled against the API is unaffected: its own DEX
    // is rewritten with the same table in the same boot, so the reference and the definition still
    // agree. Off, every injected process maps a DEX that says `org.aevix.core.Main` outright.
    val isDexObfuscateEnabled: Boolean = !BuildConfig.DEBUG,
    //
    // This one is the Xposed API call protection. On, `de.robv.android.xposed.*` and
    // `io.github.libxposed.api.Xposed*` join the table, and code the framework never processed — a
    // DEX a module loads at run time — spells those names out and finds nothing. That is the
    // protection, and it is also what breaks a module whose implementation lives in a dynamically
    // loaded class rather than in the entry class: it installs nothing and reports nothing.
    //
    // Off here, deliberately. The cost is the API packages' names sitting in the framework DEX as
    // plain strings; the gain is that every module can reach the API no matter where it keeps its
    // code. It also decides PROP_RT_API_PROTECTION, which is the flag a module reads to know it
    // must not reflect into the API — with the protection off, reflection works, so the flag has to
    // be off with it. Turning either on is one line here; nothing else decides.
    val isApiProtectionEnabled: Boolean = false,
    // States initialized after system services are ready
    val isCacheReady: Boolean = false,
    val managerUid: Int = -1,
    val miscPath: Path? = null,
    val modules: Map<String, LoadedModule> = emptyMap(),
    val scopes: Map<ProcessScope, List<LoadedModule>> = emptyMap(),
    /**
     * Modules the user enabled that the framework could not load, and why.
     *
     * The gap between the two notions of "module" this daemon holds — what was asked for, which
     * lives in the database, and what can actually be loaded, which lives here. They can disagree
     * legitimately: an APK whose path cannot be resolved, or whose DEX will not parse, stays
     * configured and never loads. That difference used to be discarded, and the module simply
     * appeared to be off; it is reported now, because it is the one thing a reader in that
     * situation needs to know.
     */
    val unloadable: Map<String, Int> = emptyMap(),
)
