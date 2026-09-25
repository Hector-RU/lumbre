package com.hector.epubreader.epub

import java.net.URI
import java.net.URLDecoder

object EpubPaths {
    fun fragment(href: String): String? {
        val raw = URI(href.replace(" ", "%20")).rawFragment ?: return null
        return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").ifBlank { null }
    }
    /** URI decoding happens once; ZIP entry names themselves are never decoded. */
    fun resolve(baseFile: String, href: String): String {
        val uri = try { URI(href.replace(" ", "%20")) } catch (e: Exception) { throw InvalidEpub("Invalid resource URI", e) }
        if (uri.isAbsolute || uri.rawAuthority != null || href.startsWith('/') || '\\' in href) throw InvalidEpub("External resource")
        val decoded = URLDecoder.decode(uri.rawPath.orEmpty().replace("+", "%2B"), "UTF-8")
        if (decoded.isEmpty()) return normalize(baseFile)
        if (decoded.startsWith('/') || '\\' in decoded || ':' in decoded || '\u0000' in decoded) throw InvalidEpub("Invalid resource path")
        return normalize(baseFile.substringBeforeLast('/', "").let { if (it.isEmpty()) decoded else "$it/$decoded" })
    }

    fun normalize(path: String): String {
        if (path.startsWith('/') || '\\' in path || ':' in path || '\u0000' in path) throw InvalidEpub("Unsafe ZIP path")
        val parts = mutableListOf<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw InvalidEpub("Path escapes archive") else parts.removeAt(parts.lastIndex)
                else -> parts.add(it)
            }
        }
        return parts.joinToString("/")
    }
}
