package org.aevix.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.aevix.util.Utils
import org.aevix.impl.di.AevixBootstrap
import org.aevix.nativebridge.HookBridge

/**
 * Builder for configuring and registering hooks. [moduleId] scopes hook ids per module and is null
 * for framework hooks; [frozen] gates registration only, so a retired generation can still unhook.
 */
class AevixHookBuilder(
    private val origin: Executable,
    private val moduleId: String? = null,
    private val frozen: (() -> Boolean)? = null,
    private val defaultExceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
) : HookBuilder {

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT
    private var id: String? = null

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun setId(id: String?): HookBuilder = apply { this.id = id }

    override fun intercept(hooker: Hooker): HookHandle {
        ensureNotFrozen()
        if (Modifier.isAbstract(origin.modifiers)) {
            throw IllegalArgumentException(
                "$origin is abstract: it has no body to hook. Hook the concrete override instead."
            )
        } else if (origin.declaringClass.classLoader == AevixHookBuilder::class.java.classLoader) {
            throw IllegalArgumentException(
                "$origin belongs to Aevix itself. Hooking the framework would hook the code running your hook."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Method::class.java &&
                origin.name == "invoke"
        ) {
            throw IllegalArgumentException(
                "Method.invoke cannot be hooked: Aevix calls it to run the original of every hooked method, so a hook here would call itself forever."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Constructor::class.java &&
                origin.name == "newInstance"
        ) {
            throw IllegalArgumentException(
                "Constructor.newInstance cannot be hooked: Aevix reflects through it the same way it does Method.invoke, so a hook here would recurse."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Any::class.java &&
                origin.name == "getClass"
        ) {
            // The compiler, not the framework, is what makes this recurse: since AGP 9 R8 compiles
            // Kotlin null checks into Object.getClass(), so the dispatch calls it entering every
            // hooked method. See #798. If you meant a getClass override on another class, hook that
            // class; Class.getMethods() lists the inherited one, which is not what you want.
            throw IllegalArgumentException(
                "Object.getClass cannot be hooked: Aevix's dispatch calls it entering every hooked method, so a hook here would call itself forever."
            )
        }

        val resolvedMode =
            if (exceptionMode == ExceptionMode.DEFAULT) defaultExceptionMode else exceptionMode
        val id = this.id
        val record = AevixHookRecord(hooker, priority, resolvedMode, id)

        // A framework hook. No module, so no id to scope and nothing to serialise against.
        val moduleId = this.moduleId ?: return register(record, null)

        synchronized(AevixHookRegistry.lockOf(moduleId)) {
            // Checked again under the lock a reload takes to freeze old code, so a registration
            // cannot slip in between the check above and the snapshot the successor is handed.
            ensureNotFrozen()

            // Same module, same executable, same id: the interface says this replaces the old hook
            // atomically and invalidates its handle, rather than installing a second one. The
            // replacement carries this builder's priority and exception mode, because it is a new
            // hook - only the handle-based replaceHook is specified to inherit them.
            if (id != null) {
                AevixHookRegistry.findId(moduleId, origin, id)?.takeIf { it.isLive }?.let {
                    return it.swapLocked(record)
                }
            }
            return register(record, moduleId)
        }
    }

    private fun ensureNotFrozen() {
        if (frozen?.invoke() == true) {
            throw IllegalStateException(
                "This module generation has been retired by a hot reload and cannot register hooks"
            )
        }
    }

    /** Installs [record] natively and records the handle against its owner. */
    private fun register(record: AevixHookRecord, moduleId: String?): HookHandle {
        if (
            !HookBridge.hookMethod(
                true,
                origin,
                AevixNativeHooker::class.java,
                record.priority,
                record,
            )
        ) {
            throw HookFailedError("Cannot hook $origin")
        }

        val handle = AevixHookHandle(origin, moduleId, record)
        if (moduleId != null) {
            AevixHookRegistry.track(moduleId, handle)
            record.id?.let { AevixHookRegistry.claimId(moduleId, origin, it, handle) }
        }
        return handle
    }

    companion object {
        /**
         * The hooks [moduleId] currently has installed, which is what a reload hands to the new
         * generation. Taken after old code has been frozen, so nothing can be added to it behind
         * the successor's back.
         */
        fun snapshotHandles(moduleId: String): List<HookHandle> =
            AevixHookRegistry.liveHandles(moduleId)

        /** The lock a reload holds while it freezes old code. */
        fun lockOf(moduleId: String): Any = AevixHookRegistry.lockOf(moduleId)
    }
}

/**
 * The native callback entrypoint. Instantiated natively by [HookBridge] when a hooked method is
 * hit.
 */
private val EMPTY_HOOK_ARGUMENTS = emptyArray<Any?>()

class AevixNativeHooker<T : Executable>(private val method: T) {

    private val isStatic = Modifier.isStatic(method.modifiers)
    private val returnType = if (method is Method) method.returnType else null

    /**
     * Invoked by C++ via JNI.
     *
     * Nothing in here is allowed to throw. This runs inside the process that called the hooked
     * method — for a framework hook installed in system_server, that is the process every other
     * process on the device depends on. An exception raised here does not read as "a hook failed";
     * it reads as the hooked method throwing where no caller expects it, and for
     * `handleSystemServerProcess` or `startBootstrapServices` that ends the system server and
     * restarts the framework. So the body is contained, and every failure degrades to running the
     * original.
     */
    fun callback(args: Array<Any?>): Any? {
        return try {
            dispatch(args)
        } catch (t: Throwable) {
            Utils.logE("Hook chain for $method failed; running the original", t)
            try {
                if (isStatic) {
                    invokeOriginalSafely(null, args)
                } else {
                    invokeOriginalSafely(
                        args.firstOrNull(),
                        if (args.size <= 1) EMPTY_HOOK_ARGUMENTS else args.copyOfRange(1, args.size),
                    )
                }
            } catch (fallback: Throwable) {
                // The original threw as well. That is its own answer and the caller is entitled to
                // it, so it is handed over rather than swallowed.
                throw fallback
            }
        }
    }

    private fun dispatch(args: Array<Any?>): Any? {
        val thisObject = if (isStatic) null else args.firstOrNull()
        val actualArgs =
            when {
                isStatic -> args
                // `<= 1` rather than `== 1`: a zero-length array has no `this` to strip either, and
                // the branch below would throw on it. The trampoline should never hand one over,
                // but this is the only arm that can fail on input it does not control.
                args.size <= 1 -> EMPTY_HOOK_ARGUMENTS
                else -> args.copyOfRange(1, args.size)
            }

        // Null means every hook was removed after this trampoline was entered.
        val snapshots =
            HookBridge.callbackSnapshot(AevixHookRecord::class.java, method)
                ?: return invokeOriginalSafely(thisObject, actualArgs)

        @Suppress("UNCHECKED_CAST") val modernHooks = snapshots[0] as Array<AevixHookRecord>
        val legacyHooks = snapshots[1]

        // Fast path: No hooks active
        if (modernHooks.isEmpty() && legacyHooks.isEmpty()) {
            return invokeOriginalSafely(thisObject, actualArgs)
        }

        val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
            val delegate = AevixBootstrap.delegate
            if (legacyHooks.isNotEmpty() && delegate != null) {
                delegate.processLegacyHook(method, tObj, tArgs, legacyHooks) {
                    invokeOriginalSafely(tObj, tArgs)
                }
            } else {
                invokeOriginalSafely(tObj, tArgs)
            }
        }

        val rootChain = AevixChain(method, thisObject, actualArgs, modernHooks, 0, terminal)

        val result = rootChain.proceed()

        return coerceReturn(result)
    }

    /**
     * Converts what the chain returned into something the trampoline can hand back.
     *
     * The trampoline is a native call into a method whose return type it has already committed to,
     * so `null` where an `int` is expected and a `String` where an `Object` is expected are both
     * read as memory it can reinterpret — not as a type error it can report. A wrong value here
     * therefore has to be turned into a right-typed one before it gets there, and a hook that
     * returns nothing for a primitive gets the primitive's zero value.
     */
    private fun coerceReturn(result: Any?): Any? {
        val returnType = this.returnType ?: return result
        if (returnType == Void.TYPE) return result

        if (result == null) {
            if (!returnType.isPrimitive) return null
            Utils.logD(
                "Hook returned null for primitive ${returnType.name} on $method; using the default value"
            )
            return defaultValueOf(returnType)
        }

        // Use the JNI bridge for the most reliable type check across ClassLoaders
        if (HookBridge.instanceOf(result, returnType) || isBoxingCompatible(result, returnType)) {
            return result
        }

        Utils.logD("Hook return type mismatch. Expected ${returnType.name}, got ${result.javaClass.name}")
        return if (returnType.isPrimitive) defaultValueOf(returnType) else null
    }

    /** The zero value of a primitive type, which is what an unhelpful hook falls back to. */
    private fun defaultValueOf(type: Class<*>): Any =
        when (type) {
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Boolean::class.javaPrimitiveType -> false
            Double::class.javaPrimitiveType -> 0.0
            Float::class.javaPrimitiveType -> 0f
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> '\u0000'
            Short::class.javaPrimitiveType -> 0.toShort()
            else -> 0
        }

    /** Handles primitive boxing compatibility (e.g., Integer object vs int primitive). */
    private fun isBoxingCompatible(obj: Any, targetType: Class<*>): Boolean {
        if (!targetType.isPrimitive) return false
        return when (targetType) {
            Int::class.javaPrimitiveType -> obj is Int
            Long::class.javaPrimitiveType -> obj is Long
            Boolean::class.javaPrimitiveType -> obj is Boolean
            Double::class.javaPrimitiveType -> obj is Double
            Float::class.javaPrimitiveType -> obj is Float
            Byte::class.javaPrimitiveType -> obj is Byte
            Char::class.javaPrimitiveType -> obj is Char
            Short::class.javaPrimitiveType -> obj is Short
            else -> false
        }
    }

    /** Safely invokes the original method, unwrapping InvocationTargetExceptions. */
    private fun invokeOriginalSafely(tObj: Any?, tArgs: Array<Any?>): Any? {
        return try {
            HookBridge.invokeOriginalMethod(method, tObj, *tArgs)
        } catch (ite: InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }
}
