package org.aevix.service

import android.app.ActivityThread
import android.os.Binder
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Parcel
import hidden.HiddenApiBridge.Binder_allowBlocking
import hidden.HiddenApiBridge.Context_getActivityToken
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.aevix.ipc.IAevixDaemon
import org.aevix.util.Log

/**
 * Manages manual Binder transactions for the Aevix framework.
 *
 * This service is not registered in ServiceManager. Instead, the Zygisk native module intercepts
 * [Binder.execTransact] and redirects calls with the [TRANSACTION_CODE] to this class.
 */
object BridgeService {
    private const val TRANSACTION_CODE =
        ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code
    private const val TAG = "AevixZygiskBridge"

    /** Actions supported by the manual IPC bridge. */
    private enum class Action {
        UNKNOWN,
        SEND_BINDER, // Daemon sending the system service binder
        GET_BINDER, // Process requesting its specific application service
    }

    @Volatile private var serviceBinder: IBinder? = null

    @Volatile private var service: IAevixDaemon? = null

    /**
     * Runs the hand-off to the daemon off the transaction's own thread.
     *
     * Every process on the device that is in scope asks for its binder here, and asks from its own
     * fork, while `system_server` holds one of its binder threads for the answer. Answering means a
     * synchronous binder call into the daemon, and the daemon was -- until its cache stopped being
     * rebuilt on the calling thread -- able to spend hundreds of milliseconds on a disk walk before
     * it replied. Sixteen of those at once is the whole pool, and a framework with no free binder
     * thread is a framework the watchdog kills.
     *
     * Cached, so a daemon that stops answering costs threads rather than correctness: each waiter is
     * abandoned after [ATTACH_TIMEOUT_MS] and its task finishes or does not, on its own.
     */
    private val attachCalls = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "aevix-bridge-attach").apply { isDaemon = true }
    }

    /** How long `system_server` will hold a binder thread waiting for the daemon's answer. */
    private const val ATTACH_TIMEOUT_MS = 2000L

    /** Cleans up service references if the remote Aevix daemon crashes. */
    private val serviceRecipient: DeathRecipient = DeathRecipient {
        Log.e(TAG, "Aevix daemin service died.")
        serviceBinder?.unlinkToDeath(this.serviceRecipient, 0)
        serviceBinder = null
        service = null
    }

    /** Returns the active Aevix daemin service interface. */
    @JvmStatic fun getService(): IAevixDaemon? = service

    /**
     * Initializes the client-side connection to the Aevix daemin service.
     *
     * @param binder The raw binder for [IAevixDaemon].
     */
    private fun receiveFromBridge(binder: IBinder?) {
        if (binder == null) {
            Log.e(TAG, "Received null binder from bridge.")
            return
        }

        // Cleanup old death recipient if we are re-initializing
        val token = Binder.clearCallingIdentity()
        try {
            serviceBinder?.unlinkToDeath(serviceRecipient, 0)
        } finally {
            Binder.restoreCallingIdentity(token)
        }

        // Allow blocking calls since we are often in a synchronous fork path
        val blockingBinder = Binder_allowBlocking(binder)
        serviceBinder = blockingBinder
        service = IAevixDaemon.Stub.asInterface(blockingBinder)

        runCatching { blockingBinder.linkToDeath(serviceRecipient, 0) }
            .onFailure { Log.e(TAG, "Failed to link to service death", it) }

        // Provide the system context to the service so it can manage system-wide states
        runCatching {
                val activityThread = ActivityThread.currentActivityThread()
                val at = activityThread.applicationThread as android.app.IApplicationThread
                val atBinder = at.asBinder()
                val systemCtx = activityThread.systemContext
                service?.dispatchSystemServerContext(atBinder, Context_getActivityToken(systemCtx))
            }
            .onFailure { Log.e(TAG, "Failed to dispatch system context", it) }

        Log.i(TAG, "Aevix daemin service binder linked.")
    }

    /** Handles manual parcel transactions. Called via reflection/JNI from the native hook. */
    @JvmStatic
    fun onTransact(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return try {
            val actionIdx = data.readInt()
            val action = Action.values().getOrElse(actionIdx) { Action.UNKNOWN }

            Log.d(TAG, "onTransact: action=$action, callerUid=${Binder.getCallingUid()}")

            when (action) {
                Action.SEND_BINDER -> {
                    // Only allow root (UID 0) to push the initial service binder
                    if (Binder.getCallingUid() == 0) {
                        receiveFromBridge(data.readStrongBinder())
                        reply?.writeNoException()
                        true
                    } else false
                }

                Action.GET_BINDER -> {
                    val processName = data.readString()
                    val heartBeat = data.readStrongBinder()
                    // Read here rather than inside the call below: these are the kernel's answer
                    // about whoever sent this transaction, and a pooled thread has no transaction
                    // of its own to be told about.
                    val callerUid = Binder.getCallingUid()
                    val callerPid = Binder.getCallingPid()
                    val daemon = service

                    val appBinder: IBinder? =
                        if (daemon == null || heartBeat == null) null
                        else {
                            // Bound outside the lambda: the AIDL parameter is non-null at the
                            // Kotlin level, and a smart cast does not survive the capture.
                            val heartbeat = heartBeat
                            val pending =
                                attachCalls.submit(
                                    Callable {
                                        runCatching {
                                                daemon
                                                    .attachProcess(
                                                        callerUid, callerPid, processName, heartbeat
                                                    )
                                                    ?.asBinder()
                                            }
                                            .getOrNull()
                                    })
                            runCatching { pending.get(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                                .onFailure {
                                    if (it is TimeoutException) {
                                        // Answering "no" costs this one process its modules.
                                        // Not answering costs the framework its binder pool.
                                        Log.w(
                                            TAG,
                                            "Daemon did not answer within ${ATTACH_TIMEOUT_MS}ms" +
                                                " for $processName; starting it unhooked",
                                        )
                                    }
                                }
                                .getOrNull()
                        }

                    if (appBinder != null && reply != null) {
                        reply.writeNoException()
                        reply.writeStrongBinder(appBinder)
                        true
                    } else false
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling bridge transaction", e)
            false
        }
    }

    /**
     * Entry point for the JNI hook in [IPCBridge.cpp].
     *
     * @param obj The Binder object being called.
     * @param code The transaction code.
     * @param dataObj Native pointer to the data Parcel.
     * @param replyObj Native pointer to the reply Parcel.
     * @param flags Transaction flags.
     * @return True if the transaction was handled.
     */
    @JvmStatic
    fun execTransact(obj: IBinder, code: Int, dataObj: Long, replyObj: Long, flags: Int): Boolean {
        if (code != TRANSACTION_CODE) return false

        val data = dataObj.asParcel()
        val reply = replyObj.asParcel()

        if (data == null || reply == null) {
            Log.w(TAG, "Transaction dropped: null parcel pointers.")
            return false
        }

        return try {
            onTransact(data, reply, flags)
        } catch (e: Exception) {
            if (flags and IBinder.FLAG_ONEWAY == 0) {
                reply.setDataPosition(0)
                reply.writeException(e)
            }
            Log.e(TAG, "Exception during execTransact", e)
            true // We handled it, even if by returning an exception
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
