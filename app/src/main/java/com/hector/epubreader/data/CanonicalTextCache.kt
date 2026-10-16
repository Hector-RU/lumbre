package com.hector.epubreader.data

/** A process-local LRU bounded by UTF-16 payload bytes. EPUB checksums make keys immutable. */
internal class CanonicalTextCache(private val maxBytes: Int = 8 * 1024 * 1024) {
    private val values = LinkedHashMap<Pair<String, String>, String>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized fun get(checksum: String, path: String): String? = values[checksum to path]

    @Synchronized fun put(checksum: String, path: String, text: String) {
        val key = checksum to path
        values.remove(key)?.let { bytes -= it.length * 2L }
        if (text.length * 2L > maxBytes) return
        values[key] = text
        bytes += text.length * 2L
        while (bytes > maxBytes || values.size > 128) {
            val oldest = values.entries.iterator()
            bytes -= oldest.next().value.length * 2L
            oldest.remove()
        }
    }

    @Synchronized fun remove(checksum: String) {
        val entries = values.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.first == checksum) { bytes -= entry.value.length * 2L; entries.remove() }
        }
    }
}
