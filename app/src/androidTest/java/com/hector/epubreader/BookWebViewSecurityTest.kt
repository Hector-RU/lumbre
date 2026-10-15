package com.hector.epubreader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.local.BookEntity
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.*
import com.hector.epubreader.epub.renderer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookWebViewSecurityTest {
    private class Request(private val uri: Uri) : WebResourceRequest {
        override fun getUrl() = uri
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun publicationCodeStaysBlockedWithReaderJavascriptEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("security-", ".epub", context.cacheDir)
        val input = """<html><body onload="window.attack=1"><script>window.attack=1</script><img src="missing.png" onerror="window.attack=1"/><p>Safe text</p></body></html>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("chapter.xhtml")); zip.write(input.toByteArray()); zip.closeEntry()
        }
        val resource = EpubResource("chapter", "chapter.xhtml", "application/xhtml+xml", emptySet())
        val notes = EpubResource("notes", "notes.xhtml", "application/xhtml+xml", emptySet())
        val publication = EpubPublication("Security", null, null, null, null, listOf(EpubChapter("chapter", resource.path, "Chapter")), emptyList(), mapOf(resource.path to resource, notes.path to notes))
        val book = OpenBook(BookEntity("security", "Security", null, "test", "test", null, null, null, 1), publication, file)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                for (mode in listOf("scroll", "pages", "paragraphs")) {
                    val finished = CountDownLatch(1)
                    val result = AtomicReference<String>()
                    scenario.onActivity { activity ->
                        val web = WebView(activity)
                        activity.setContentView(web)
                        web.settings.javaScriptEnabled = true
                        web.settings.allowFileAccess = false
                        web.settings.allowContentAccess = false
                        web.settings.blockNetworkLoads = true
                        val p = ReaderPreferences(readingMode = mode)
                        var opened: String? = null
                        val client = BookWebViewClient(book, p, EpubContent.Colors("#FFFFFF", "#000000", "#0000FF", "#CCCCCC", false), 1f,
                            onLink = { _, _ -> }, onReady = { view ->
                                view.evaluateJavascript("typeof window.attack + '|' + document.querySelectorAll('script,[onload],[onerror]').length") {
                                    result.set(it); finished.countDown()
                                }
                            }, onError = {}, onResourceLink = { path, _ -> opened = path })
                        assertTrue(client.shouldOverrideUrlLoading(web, Request(Uri.parse(client.url(notes.path, "note")))))
                        assertEquals(notes.path, opened)
                        opened = null
                        assertTrue(client.shouldOverrideUrlLoading(web, Request(Uri.parse("https://example.com/notes.xhtml"))))
                        assertNull(opened)
                        val response = client.shouldInterceptRequest(web, Request(Uri.parse("https://outside.invalid/missing.png")))
                        assertEquals(403, response.statusCode); response.data.close()
                        web.webViewClient = client
                        web.loadUrl(client.url(resource.path))
                    }
                    assertTrue("Reader must render $mode", finished.await(15, TimeUnit.SECONDS))
                    assertEquals("\"undefined|0\"", result.get())
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        (root.getChildAt(0) as WebView).apply { stopLoading(); destroy() }
                        root.removeAllViews()
                    }
                }
            }
        } finally { file.delete() }
    }
}
