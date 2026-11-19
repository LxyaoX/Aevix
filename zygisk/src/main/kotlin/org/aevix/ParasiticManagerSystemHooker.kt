package org.aevix

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import java.lang.reflect.Field
import org.aevix.util.Utils
import org.aevix.impl.hookers.HandleSystemServerProcessHooker
import org.aevix.impl.hooks.AevixHookBuilder
import org.aevix.service.BridgeService

/**
 * Handles System-Server side logic for the Parasitic Manager.
 *
 * When a user tries to open the Aevix Manager, the system normally wouldn't know how to handle it
 * because it isn't "installed." This class intercepts the activity resolution and tells the system
 * to launch it in a special process.
 */
class ParasiticManagerSystemHooker : HandleSystemServerProcessHooker.Callback {

    companion object {
        @JvmStatic
        fun start() {
            // Register this class as the handler for system_server initialization.
            // This ensures the hook is deferred until the System Server ClassLoader is fully ready.
            HandleSystemServerProcessHooker.callback = ParasiticManagerSystemHooker()
        }
    }

    @SuppressLint("PrivateApi")
    override fun onSystemServerLoaded(classLoader: ClassLoader) {
        android.util.Log.i("AevixDiag", "PMSH.onSystemServerLoaded cl=$classLoader")
        hookActivityResolution(classLoader)
        hookSplashScreenSuppression(classLoader)
    }

    private fun hookActivityResolution(classLoader: ClassLoader) {
        runCatching {
                // Android versions change the name of the internal class responsible for activity
                // tracking.
                // We check the most likely candidates based on API levels (9.0 through 14+).
                val supervisorClass =
                    try {
                        // Android 12.0 - 14+
                        Class.forName(
                            "com.android.server.wm.ActivityTaskSupervisor",
                            false,
                            classLoader,
                        )
                    } catch (e: ClassNotFoundException) {
                        try {
                            // Android 10 - 11
                            Class.forName(
                                "com.android.server.wm.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        } catch (e2: ClassNotFoundException) {
                            // Android 8.1 - 9
                            Class.forName(
                                "com.android.server.am.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        }
                    }

                // Locate every resolveActivity overload, not whichever one declaredMethods happens
                // to list first. There are two, and the split is the bug that made this stop
                // working: ActivityStarter calls resolveActivity(Intent, ResolveInfo, int,
                // ProfilerInfo) for a start that already carries a component, while resolveIntent
                // -- the path a bare intent takes -- calls the (Intent, String, int, ProfilerInfo)
                // one. Only one of them was hooked, and which one depended on reflection order.
                //
                // Both callers that open the manager name the shell's activity explicitly:
                // ManagerService.openManager() builds a ComponentName, and action.sh passes
                // com.android.shell/.BugreportWarningActivity on the command line. So the start
                // took the unhooked path, the processName was never rewritten, the fork stayed in
                // com.android.shell -- where no manager hook is loaded -- and the user was shown
                // that activity's own UI. Shell's BugreportWarningActivity is the "an error report
                // may contain sensitive data" dialog, which is exactly what was reported.
                val resolveMethods =
                    supervisorClass.declaredMethods.filter { it.name == "resolveActivity" }
                android.util.Log.i(
                    "AevixDiag",
                    "resolveActivity overloads found=${resolveMethods.size}: " +
                        resolveMethods.joinToString { m ->
                            "(${m.parameterTypes.joinToString { t -> t.simpleName }})->" +
                                m.returnType.simpleName
                        },
                )
                if (resolveMethods.isEmpty()) {
                    Utils.logD("ActivityTaskSupervisor has no resolveActivity; the manager will not redirect")
                }

                for (resolveMethod in resolveMethods) {
                    runCatching {
                            AevixHookBuilder(resolveMethod).intercept { chain ->
                                // Execute the original resolution first
                                val result = chain.proceed()

                                val intent = chain.args[0] as? Intent ?: return@intercept result
                                val isManagerLaunch =
                                    intent.hasCategory(
                                        BuildConfig.ManagerPackageName + ".LAUNCH_MANAGER")
                                if (isManagerLaunch) {
                                    android.util.Log.i(
                                        "AevixDiag",
                                        "resolveActivity HIT manager launch: comp=${intent.component} " +
                                            "resultType=${result?.javaClass?.name} result=$result",
                                    )
                                }
                                if (!isManagerLaunch) return@intercept result

                                // The result is normally an ActivityInfo. Some ROM revisions wrap
                                // it in a holder (ResolvedActivityInfo); pull the ActivityInfo out
                                // by reflection so the redirect works with either shape.
                                var holder: Any? = null
                                val originalActivityInfo: ActivityInfo? =
                                    when (result) {
                                        is ActivityInfo -> result
                                        else ->
                                            result?.javaClass?.let { rc ->
                                                val field =
                                                    runCatching {
                                                            rc.getDeclaredField("activityInfo")
                                                                .apply { isAccessible = true }
                                                        }
                                                        .getOrNull()
                                                val value = field?.get(result) as? ActivityInfo
                                                if (value != null) holder = result
                                                value
                                            }
                                    }
                                if (originalActivityInfo == null) {
                                    android.util.Log.e(
                                        "AevixDiag",
                                        "resolveActivity result has no ActivityInfo: ${result?.javaClass?.name}",
                                    )
                                    return@intercept result
                                }
                                android.util.Log.i(
                                    "AevixDiag",
                                    "redirecting aInfo.packageName=${originalActivityInfo.packageName} " +
                                        "expected=${BuildConfig.InjectedPackageName}",
                                )

                                // We only intercept if it's currently resolving to the shell/fallback
                                if (originalActivityInfo.packageName !=
                                    BuildConfig.InjectedPackageName)
                                    return@intercept result

                                // --- Redirection Logic ---
                                // We create a copy of the ActivityInfo to avoid polluting the
                                // system's cache.
                                val redirectedInfo =
                                    ActivityInfo(originalActivityInfo).apply {
                                        // Force the manager to run in its own dedicated process name
                                        processName = BuildConfig.ManagerPackageName

                                        // Set a standard theme so transition animations work correctly
                                        theme = android.R.style.Theme_DeviceDefault_Settings

                                        // Ensure the activity isn't excluded from recents by host flags
                                        flags =
                                            flags and
                                                (ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS or
                                                        ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS)
                                                    .inv()
                                    }

                                // Notify the bridge service that we are about to start the manager
                                BridgeService.getService()?.preStartManager()
                                android.util.Log.i(
                                    "AevixDiag",
                                    "redirect done -> processName=${redirectedInfo.processName} name=${redirectedInfo.name}",
                                )

                                // If the ROM wrapped the ActivityInfo, put the redirected copy
                                // back into the same holder so the caller sees our values.
                                if (holder != null) {
                                    runCatching {
                                            holder!!.javaClass
                                                .getDeclaredField("activityInfo")
                                                .apply { isAccessible = true }
                                                .set(holder, redirectedInfo)
                                        }
                                        .onFailure {
                                            android.util.Log.e(
                                                "AevixDiag", "failed to set redirected ActivityInfo back", it)
                                        }
                                    return@intercept holder
                                }
                                redirectedInfo
                            }
                        }
                        .onFailure {
                            Utils.logE(
                                "Failed to hook resolveActivity(${resolveMethod.parameterCount} args)",
                                it)
                        }
                }

                Utils.logD(
                    "Successfully hooked Activity Supervisor for Manager redirection " +
                        "(${resolveMethods.size} resolveActivity overload(s)).")
            }
            .onFailure { Utils.logE("Failed to hook system server activity resolution", it) }
    }

    private fun hookSplashScreenSuppression(classLoader: ClassLoader) {
        // StartingSurfaceController is where the splash surface is created from API 31 on, and 31
        // is below the floor here. The ActivityRecord.showStartingWindow path that predates it is
        // gone with the releases it served.
        runCatching {
                val controllerClass =
                    Class.forName(
                        "com.android.server.wm.StartingSurfaceController",
                        false,
                        classLoader,
                    )
                val createMethod =
                    controllerClass.declaredMethods.first {
                        it.name == "createSplashScreenStartingSurface"
                    }

                val activityRecordClass =
                    Class.forName("com.android.server.wm.ActivityRecord", false, classLoader)

                // Cache the field to avoid overhead during hook execution
                val infoField: Field =
                    activityRecordClass.getDeclaredField("info").apply { isAccessible = true }

                AevixHookBuilder(createMethod).intercept { chain ->
                    // Both reads are guarded: this runs on the window manager's own thread for
                    // every activity start, and a throw here would be answered by the protective
                    // mode replaying the original - which is the right outcome, but only after the
                    // exception has crossed a path that measures its own latency. An unknown shape
                    // simply means "not ours".
                    val activityRecord = chain.args.firstOrNull()
                    val info =
                        if (activityRecord == null) null
                        else runCatching { infoField.get(activityRecord) as? ActivityInfo }
                            .getOrNull()

                    if (info?.processName == BuildConfig.ManagerPackageName) {
                        Utils.logD("Suppressing the splash screen for Aevix Manager.")
                        return@intercept null
                    }
                    chain.proceed()
                }
            }
            .onFailure { Utils.logE("Failed to hook StartingSurfaceController", it) }
    }
}
