package com.gitmob.app.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 响应读取也在 OkHttp 工作线程完成；取消同时关闭 socket，不阻塞 Main。 */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(read)
                    continuation.resume(value)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        })
    }
}
