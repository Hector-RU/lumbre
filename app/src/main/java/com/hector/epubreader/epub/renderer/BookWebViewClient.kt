package com.hector.epubreader.epub.renderer

import android.net.Uri
import android.webkit.*
import androidx.core.net.toUri
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.epub.BoundedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.zip.ZipFile

class BookWebViewClient(
    private val book: OpenBook,
    private val preferences: ReaderPreferences,
    private val colors: EpubContent.Colors,
    private val fontScale: Float,
    private val onLink: (Int, String?) -> Unit,
    private val onReady: (WebView) -> Unit,
    private val onError: () -> Unit
) : WebViewClient() {
    val host = "book-${book.record.id}.invalid"
    fun url(path: String, fragment: String? = null): String = Uri.Builder().scheme("https").authority(host).path("/$path").fragment(fragment).build().toString()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (!internal(uri)) return true
        val path = uri.path.orEmpty().removePrefix("/")
        val chapter = book.publication.chapters.indexOfFirst { it.path == path }
        if (chapter >= 0) {
            val currentPath = view.url?.toUri()?.path
            if (currentPath == uri.path && uri.fragment != null) return false
            onLink(chapter, uri.fragment)
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        if (!internal(request.url) || request.method != "GET") return blocked()
        val path = request.url.path.orEmpty().removePrefix("/")
        val resource = book.publication.resources[path] ?: return blocked()
        return try {
            if (resource.mediaType in setOf("application/xhtml+xml", "text/html")) {
                val content = EpubContent.render(EpubParser().readResource(book.file, path), preferences, fontScale, colors)
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers(), ByteArrayInputStream(content.toByteArray()))
            } else {
                if (!(resource.mediaType.startsWith("image/") || resource.mediaType.startsWith("font/") || resource.mediaType in setOf("text/css", "application/vnd.ms-opentype", "application/font-sfnt", "application/font-woff", "application/x-font-ttf", "application/x-font-opentype"))) return blocked()
                val zip = ZipFile(book.file)
                try {
                    val entry = zip.getEntry(path) ?: run { zip.close(); return blocked() }
                    val limit = if (resource.mediaType == "text/css") 2L * 1024 * 1024 else 32L * 1024 * 1024
                    if (entry.size > limit) { zip.close(); return blocked() }
                    val stream = object : FilterInputStream(BoundedInputStream(zip.getInputStream(entry), limit)) {
                        override fun close() { try { super.close() } finally { zip.close() } }
                    }
                    WebResourceResponse(resource.mediaType, if (resource.mediaType == "text/css") "UTF-8" else null, 200, "OK", headers(), stream)
                } catch (e: Exception) { zip.close(); throw e }
            }
        } catch (_: java.io.IOException) {
            view.post(onError)
            blocked()
        }
    }

    private fun internal(uri: Uri) = uri.scheme == "https" && uri.host == host && uri.port == -1 && uri.userInfo == null
    private fun headers() = mapOf(
        "Content-Security-Policy" to "default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
        "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store"
    )
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", headers(), ByteArrayInputStream(ByteArray(0)))
    override fun onPageFinished(view: WebView, url: String) { onReady(view) }
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) onError() }
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { onError(); return true }
}
