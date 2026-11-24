// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
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

internal suspend fun OkHttpClient.onlineCopyToSink(
    request: Request,
    sink: BufferedSink,
    limit: Int,
): Long = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val written = response.use {
                        if (!it.isSuccessful) throw OnlineHttpException(it.code)
                        copyBounded(it.body.byteStream(), sink, limit)
                    }
                    if (continuation.isActive) continuation.resume(written)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        },
    )
}
