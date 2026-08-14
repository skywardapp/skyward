package dev.fritze.skyward.core.sources

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

actual fun gzipCompress(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}

actual fun gzipDecompress(data: ByteArray): ByteArray =
    GZIPInputStream(data.inputStream()).use { it.readBytes() }
