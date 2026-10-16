package com.hector.epubreader

import com.hector.epubreader.epub.FontObfuscation
import org.junit.Assert.*
import org.junit.Test

class FontObfuscationTest {
    // Published SHA-1("abc") test vector, independent of the decoder.
    private val key = "a9993e364706816aba3e25717850c26c9cd0d89d".chunked(2).map { it.toInt(16) }
    private val original = ByteArray(2048) { (it % 251).toByte() }
    private fun encoded() = original.mapIndexed { index, byte ->
        if (index < 1040) (byte.toInt() xor key[index % key.size]).toByte() else byte
    }.toByteArray()

    @Test fun decodesIndividualAndBulkReadsWithoutChangingTheTail() {
        val stream = FontObfuscation(" a\tb\nc\r ").decode(encoded().inputStream())
        assertEquals(original[0].toInt(), stream.read())
        assertArrayEquals(original.copyOfRange(1, original.size), stream.readBytes())
        assertEquals(-1, stream.read())
    }
    @Test fun skipKeepsTheKeyAndBoundaryAligned() {
        val stream = FontObfuscation("abc").decode(encoded().inputStream())
        assertEquals(1037L, stream.skip(1037))
        val bytes = ByteArray(20)
        assertEquals(16, stream.read(bytes, 2, 16))
        assertArrayEquals(original.copyOfRange(1037, 1053), bytes.copyOfRange(2, 18))
    }
    @Test fun handlesShortAndEmptyFonts() {
        assertArrayEquals(original.copyOf(17), FontObfuscation("abc").decode(encoded().copyOf(17).inputStream()).readBytes())
        assertEquals(-1, FontObfuscation("abc").decode(byteArrayOf().inputStream()).read())
    }
}
