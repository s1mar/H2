package com.kin.familyhealth.core

/**
 * Small shared result wrapper so feature agents don't each invent their own.
 */
sealed class Result<out T> {
    data class Success<out T>(val value: T) : Result<T>()
    data class Failure(val error: Throwable) : Result<Nothing>()

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun getOrNull(): T? = (this as? Success)?.value

    companion object {
        inline fun <T> runCatching(block: () -> T): Result<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t)
        }
    }
}
