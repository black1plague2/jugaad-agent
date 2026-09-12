package com.jugaad.agent.core

/**
 * Tiny result type. Named [Outcome] to avoid colliding with kotlin.Result and to
 * keep call-sites explicit about the failure branch (capture / DSP / IO can all fail).
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val message: String, val cause: Throwable? = null) : Outcome<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value

    fun <R> map(f: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(f(value))
        is Err -> this
    }

    fun onErr(f: (Err) -> Unit): Outcome<T> {
        if (this is Err) f(this)
        return this
    }

    companion object {
        inline fun <T> catching(context: String, block: () -> T): Outcome<T> =
            try {
                Ok(block())
            } catch (t: Throwable) {
                Err("$context: ${t.message ?: t::class.java.simpleName}", t)
            }
    }
}
