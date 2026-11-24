// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.BufferedSink

internal class OnlineHttpException(val code: Int) : IOException("请求失败（$code）")

internal suspend fun OkHttpClient.onlineBytes(request: Request, limit: Int): ByteArray =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw OnlineHttpException(it.code)
                            boundedRead(it.body.byteStream(), limit)
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            },
        )
    }

/**
 * Executes synchronously on [Dispatchers.IO] with cancellation wired to [Call.cancel] so the call
 * has completely stopped writing to [sink] before cancellation returns to the caller.
 */
internal suspend fun OkHttpClient.onlineCopyToSink(
    request: Request,
    sink: BufferedSink,
    limit: Int,
): Long =
    withContext(Dispatchers.IO) {
        coroutineScope {
            coroutineContext.ensureActive()
            val call = newCall(request)
            val done = AtomicBoolean(false)
            val cancelWatcher =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!done.get()) call.cancel()
                    }
                }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw OnlineHttpException(response.code)
                    copyBounded(response.body.byteStream(), sink, limit)
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                if (e is CancellationException) throw e
                throw e
            } finally {
                done.set(true)
                cancelWatcher.cancel()
            }
        }
    }
