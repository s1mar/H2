package com.kin.familyhealth.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatcher bundle so feature code can be tested without
 * hard-coding kotlinx.coroutines.Dispatchers.* directly.
 */
data class DispatcherProvider(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val main: CoroutineDispatcher = Dispatchers.Main,
    val default: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        val Default = DispatcherProvider()
    }
}
