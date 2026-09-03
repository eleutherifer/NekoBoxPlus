package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.AppLogLevel
import io.nekohasekai.sagernet.AppLogLevelController
import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        if (!AppLogLevelController.allows(AppLogLevel.DEBUG)) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        if (!AppLogLevelController.allows(AppLogLevel.DEBUG)) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        if (!AppLogLevelController.allows(AppLogLevel.INFO)) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        if (!AppLogLevelController.allows(AppLogLevel.INFO)) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        if (!AppLogLevelController.allows(AppLogLevel.WARNING)) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        if (!AppLogLevelController.allows(AppLogLevel.WARNING)) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        if (!AppLogLevelController.allows(AppLogLevel.WARNING)) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        if (!AppLogLevelController.allows(AppLogLevel.ERROR)) return
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        if (!AppLogLevelController.allows(AppLogLevel.ERROR)) return
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        if (!AppLogLevelController.allows(AppLogLevel.ERROR)) return
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}
