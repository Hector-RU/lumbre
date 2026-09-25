package com.hector.epubreader.epub

import java.io.FilterInputStream
import java.io.InputStream

/** Enforces actual inflated bytes, even when a hostile ZIP lies about its entry size. */
class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var consumed = 0L
    private fun account(count: Int) {
        if (count > 0) {
            consumed += count
            if (consumed > limit) throw InvalidEpub("Inflated resource exceeds limit")
        }
    }
    override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also(::account)
    override fun skip(count: Long): Long {
        var remaining = count.coerceAtLeast(0)
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
        return count.coerceAtLeast(0) - remaining
    }
    override fun markSupported() = false
    override fun mark(readlimit: Int) = Unit
    override fun reset(): Unit = throw java.io.IOException("Reset is not supported")
}
