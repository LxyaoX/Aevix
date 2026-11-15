package org.aevix.daemon.utils

import android.os.SharedMemory

object ObfuscationManager {
  /**
   * Rewrites the prefixes this boot's table covers, in a DEX already mapped into [memory].
   *
   * [apiProtection] selects whether the Xposed API packages are among them. Off, the framework's
   * own names are still renamed — a module compiled against the API is unaffected either way,
   * because its DEX is rewritten with the same table — but the API packages keep their real names,
   * so code that loads a DEX at run time can still find them.
   */
  @JvmStatic external fun obfuscateDex(memory: SharedMemory, apiProtection: Boolean): SharedMemory

  /** The prefix table in effect, dotted form to dotted form, for the same [apiProtection] answer. */
  @JvmStatic external fun getSignatures(apiProtection: Boolean): Map<String, String>
}
