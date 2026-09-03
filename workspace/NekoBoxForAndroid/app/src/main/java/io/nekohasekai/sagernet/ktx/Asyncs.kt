@file:Suppress("EXPERIMENTAL_API_USAGE")

package io.nekohasekai.sagernet.ktx

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.app.AppGraph
import kotlinx.coroutines.*

fun block(block: suspend CoroutineScope.() -> Unit): suspend CoroutineScope.() -> Unit {
    return block
}

fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit) =
    AppGraph.applicationScope.launch(AppGraph.dispatchers.default, block = block)

fun Fragment.runOnLifecycleDispatcher(block: suspend CoroutineScope.() -> Unit) =
    lifecycleScope.launch(Dispatchers.Default, block = block)

suspend fun <T> onDefaultDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(AppGraph.dispatchers.default, block = block)

fun runOnIoDispatcher(block: suspend CoroutineScope.() -> Unit) =
    AppGraph.applicationScope.launch(AppGraph.dispatchers.io, block = block)

suspend fun <T> onIoDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(AppGraph.dispatchers.io, block = block)

fun runOnMainDispatcher(block: suspend CoroutineScope.() -> Unit) =
    AppGraph.applicationScope.launch(AppGraph.dispatchers.main, block = block)

suspend fun <T> onMainDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(AppGraph.dispatchers.main, block = block)
