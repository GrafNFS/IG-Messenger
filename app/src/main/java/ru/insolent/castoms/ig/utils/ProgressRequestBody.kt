package ru.insolent.castoms.ig.utils

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.source
import java.io.File

/**
 * RequestBody с отслеживанием прогресса загрузки.
 * Колбэк вызывается на фоновом потоке OkHttp — не делать в нём UI-операций.
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (percent: Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        if (total <= 0) {
            sink.writeAll(file.source())
            onProgress(100)
            return
        }

        var uploaded = 0L
        var lastPercent = -1

        val source = file.source()
        val forwarding: Sink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                uploaded += byteCount
                val percent = ((uploaded * 100) / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }

        val bufferedSink = forwarding.buffer()
        bufferedSink.writeAll(source)
        bufferedSink.flush()
        onProgress(100)
    }
}
