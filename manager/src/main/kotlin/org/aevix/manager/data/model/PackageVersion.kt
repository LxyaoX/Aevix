package org.aevix.manager.data.model

import android.content.pm.PackageInfo

/**
 * A package's version code.
 *
 * `PackageInfo.getLongVersionCode` arrived in API 28 and the `int` field it replaced was deprecated
 * in the same release. This app's floor is 34, so there is one answer here and it is the long one;
 * the guarded read that used to be needed for 8.1 is gone with it.
 */
val PackageInfo.versionCodeCompat: Long
    get() = longVersionCode
