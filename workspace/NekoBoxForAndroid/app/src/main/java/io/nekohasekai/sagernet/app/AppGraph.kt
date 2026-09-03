package io.nekohasekai.sagernet.app

import android.os.SystemClock
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor

data class AppDispatchers(
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val main: CoroutineDispatcher,
) {
    companion object {
        val Default = AppDispatchers(
            default = Dispatchers.Default,
            io = Dispatchers.IO,
            main = Dispatchers.Main.immediate,
        )
    }
}

interface AppClock {
    fun currentTimeMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

object SystemAppClock : AppClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

class AppDependencies internal constructor(
    val dispatchers: AppDispatchers,
    val clock: AppClock,
    val applicationScope: CoroutineScope,
) {
    val databaseExecutor: Executor = dispatchers.io.asExecutor()
}

object AppGraph {
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> Logs.w(error) }

    @Volatile
    private var dependencies = createDependencies(AppDispatchers.Default, SystemAppClock)

    val dispatchers: AppDispatchers get() = dependencies.dispatchers
    val clock: AppClock get() = dependencies.clock
    val applicationScope: CoroutineScope get() = dependencies.applicationScope
    val databaseExecutor: Executor get() = dependencies.databaseExecutor

    private fun createDependencies(
        dispatchers: AppDispatchers,
        clock: AppClock,
    ) = AppDependencies(
        dispatchers = dispatchers,
        clock = clock,
        applicationScope = CoroutineScope(
            SupervisorJob() + dispatchers.default + CoroutineName("SagerNet") + exceptionHandler,
        ),
    )

    @Synchronized
    internal fun installForTest(
        dispatchers: AppDispatchers,
        clock: AppClock,
    ): AutoCloseable {
        val previous = dependencies
        val replacement = createDependencies(dispatchers, clock)
        dependencies = replacement
        return AutoCloseable {
            synchronized(this) {
                check(dependencies === replacement) { "AppGraph test installation closed out of order" }
                dependencies = previous
                replacement.applicationScope.coroutineContext[Job]?.cancel()
            }
        }
    }
}
