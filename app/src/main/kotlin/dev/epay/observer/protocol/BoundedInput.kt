package dev.epay.observer.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.bounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (output.size() <= limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (read == -1) break
        if (read > 0) output.write(buffer, 0, read)
    }
    require(output.size() <= limit) { "response_too_large" }
    return output.toByteArray()
}
