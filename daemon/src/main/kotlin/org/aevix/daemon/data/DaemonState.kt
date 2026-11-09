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
    // This is not a flag about modules: it is the framework's hiding. Before a DEX is handed to a
    // process, its entry classes are renamed to names drawn fresh on every boot, so a scan for a
    // hook framework's classes finds nothing to name. Off, every injected process maps a DEX that
    // says `org.aevix.core.Main` outright, which is what a detector reports as a hook framework
    // being present. It also drives PROP_RT_API_PROTECTION, which is what tells modules the
    // protection is on. Turning it off is one line here; nothing else decides.
    val isDexObfuscateEnabled: Boolean = !BuildConfig.DEBUG,
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
