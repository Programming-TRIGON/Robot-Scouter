package com.supercilex.robotscouter.core

import android.util.Log
import androidx.annotation.Keep
import com.crashlytics.android.Crashlytics
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

fun <T> Task<T>.logFailures(vararg hints: Any?): Task<T> {
    val trace = generateStackTrace()
    return addOnFailureListener {
        for (hint in hints) logCrashLog(hint.toString())
        CrashLogger.invoke(it.injectRoot(trace))
    }
}

fun <T> Deferred<T>.logFailures(): Deferred<T> {
    invokeOnCompletion(CrashLogger)
    return this
}

fun logCrashLog(message: String) {
    Crashlytics.log(message)
    if (BuildConfig.DEBUG) Log.d("CrashLogs", message)
}

internal fun generateStackTrace() = Thread.currentThread().stackTrace.let {
    // Skip 2 for the `stackTrace` method, 1 for this method, and 1 for the caller
    it.takeLast(it.size - 4)
}

internal fun Throwable.injectRoot(trace: List<StackTraceElement>) = apply {
    stackTrace = stackTrace.toMutableList().apply {
        addAll(0, trace)
        add(trace.size, StackTraceElement("Hack", "startOriginalStackTrace", "Hack.kt", 0))
    }.toTypedArray()
}

object CrashLogger : OnFailureListener, OnCompleteListener<Any>, CompletionHandler {
    override fun onFailure(e: Exception) {
        invoke(e)
    }

    override fun onComplete(task: Task<Any>) {
        invoke(task.exception)
    }

    override fun invoke(t: Throwable?) {
        if (t == null || t is CancellationException) return
        if (BuildConfig.DEBUG || isInTestMode) {
            Log.e("CrashLogger", "An error occurred", t)
        } else {
            Crashlytics.logException(t)
        }
    }
}

@Keep
internal class LoggingHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
        CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        Crashlytics.logException(exception)

        // Since we don't want to crash and Coroutines will call the current thread's handler, we
        // install a noop handler and then reinstall the existing one once coroutines calls the new
        // handler.
        Thread.currentThread().apply {
            // _Do_ crash the main thread to ensure we're not left in a bad state
            if (isMain) return@apply

            val removed = uncaughtExceptionHandler
            uncaughtExceptionHandler = if (removed == null) {
                ResettingHandler
            } else {
                Thread.UncaughtExceptionHandler { t, _ ->
                    t.uncaughtExceptionHandler = removed
                }
            }
        }
    }

    private object ResettingHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            t.uncaughtExceptionHandler = null
        }
    }
}
