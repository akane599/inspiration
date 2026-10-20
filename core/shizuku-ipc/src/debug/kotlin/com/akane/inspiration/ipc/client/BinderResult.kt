package com.akane.inspiration.ipc.client

/** Result of one binder read: a value, or a short machine reason from [com.akane.inspiration.ipc.model.Reasons]. */
sealed interface BinderResult<out T> {
    data class Ok<out T>(val value: T) : BinderResult<T>

    data class Failed(val reason: String) : BinderResult<Nothing>
}
