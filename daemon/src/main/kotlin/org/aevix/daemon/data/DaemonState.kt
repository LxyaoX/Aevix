package org.aevix.daemon.data

import java.nio.file.Path
import org.aevix.ipc.LoadedModule

data class ProcessScope(val processName: String, val uid: Int)

/**
 * An immutable snapshot of the Daemon's state. Any updates will generate a new copy of this class
 * and atomically swap the reference.
 */
data class DaemonState(
    // State non configurable for users.
    //
    // One flag for two things, because the framework treats them as one: the DEX hiding, and the
    // Xposed API call protection it advertises through PROP_RT_API_PROTECTION.
    //
    // On, a DEX handed to a process has its entry classes renamed to names drawn fresh on every
    // boot, so a scan for a hook framework's classes finds nothing to name; and module code that
    // loads a DEX at run time has no Xposed API to reach. The second part is the point of the
    // protection, and it is also what breaks a module whose implementation lives in a dynamically
    // loaded class rather than in the entry class: it installs nothing and reports nothing.
    //
    // Off, as here: an injected process maps a DEX that says `org.aevix.core.Main` outright, which
    // is what a detector reports as a hook framework being present. That trade is deliberate — a
    // module that silently hooks nothing is the worse failure of the two. Turning it on is one
    // line here; nothing else decides.
    val isDexObfuscateEnabled: Boolean = false,
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
