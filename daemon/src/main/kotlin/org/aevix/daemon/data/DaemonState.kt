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
    // Off, deliberately. This is the framework's API protection: it renames its own entry classes
    // on every boot, obfuscates each module's DEX before loading it, and is what
    // PROP_RT_API_PROTECTION reports to modules. It costs load time and breaks modules that read
    // their own DEX, and nothing here depends on it, so this build does without it. This field is
    // the single place that decides -- everything else only reads it.
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
