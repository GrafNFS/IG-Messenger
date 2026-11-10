package ru.insolent.castoms.ig.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Okio
import okio.Sink
import okio.buffer // ✅ Добавляем импорт extension function

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val progressListener: (bytesWritten: Long, contentLength: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val forwardingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                progressListener(bytesWritten, contentLength())
            }
        }

        // ✅ ИСПРАВЛЕНО: используем extension function
        val bufferedSink = forwardingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}