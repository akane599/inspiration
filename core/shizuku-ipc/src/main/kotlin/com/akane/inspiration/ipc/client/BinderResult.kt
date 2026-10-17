package com.akane.inspiration.ipc.client

import android.os.IBinder
import android.os.RemoteException
import com.akane.inspiration.ipc.model.Reasons
import java.lang.reflect.InvocationTargetException

/** Result of one binder read: a value, or a short machine reason from [Reasons]. */
sealed interface BinderResult<out T> {
    data class Ok<out T>(val value: T) : BinderResult<T>

    data class Failed(val reason: String) : BinderResult<Nothing>
}

/**
 * Maps a binder-call failure to a [Reasons] value. Reflection wrappers are unwrapped first so a
 * SecurityException thrown through `Method.invoke` still reads as a permission failure.
 */
internal fun failureReason(error: Throwable): String {
    val cause = (error as? InvocationTargetException)?.targetException ?: error
    return when (cause) {
        is RemoteException -> Reasons.REMOTE
        is SecurityException -> Reasons.PERMISSION
        is LinkageError, is ReflectiveOperationException -> Reasons.NO_SUCH_METHOD
        else -> Reasons.REMOTE
    }
}

/**
 * Resolves the binder and runs [block] on it, mapping a null binder and every [Exception] or
 * [LinkageError] to [onFailure]. Other [Error]s (out of memory and the like) still propagate.
 */
internal inline fun <T> withBinder(
    binderSource: () -> IBinder?,
    onFailure: (String) -> T,
    block: (IBinder) -> T,
): T = try {
    val binder = binderSource()
    if (binder == null) onFailure(Reasons.REMOTE) else block(binder)
} catch (e: Exception) {
    onFailure(failureReason(e))
} catch (e: LinkageError) {
    onFailure(failureReason(e))
}
