package com.hector.epubreader.epub

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** EPUB 3.3 section 4.4: SHA-1 of the package identifier, XOR over the first 1040 bytes. */
class FontObfuscation(identifier: String) {
    private val key = MessageDigest.getInstance("SHA-1").digest(
        identifier.filterNot { it in " \t\r\n" }.toByteArray(Charsets.UTF_8))

    fun decode(input: InputStream): InputStream = object : FilterInputStream(input) {
        private var position = 0L
        private fun decodeByte(value: Int): Int {
            if (value < 0) return value
            val decoded = if (position < 1040) value xor (key[(position % key.size).toInt()].toInt() and 255) else value
            position++
            return decoded
        }
        override fun read(): Int = decodeByte(`in`.read())
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val count = `in`.read(bytes, offset, length)
            for (i in 0 until count) bytes[offset + i] = decodeByte(bytes[offset + i].toInt() and 255).toByte()
            return count
        }
        override fun skip(count: Long): Long {
            val skipped = `in`.skip(count.coerceAtLeast(0))
            position += skipped
            return skipped
        }
        override fun markSupported() = false
        override fun reset(): Unit = throw java.io.IOException("Mark/reset unsupported")
    }
}
