package com.hector.epubreader

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.local.BookEntity
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.epub.renderer.BookWebViewClient
import com.hector.epubreader.epub.renderer.EpubContent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ObfuscatedFontRenderingTest {
    @Test fun webViewLoadsAnIdpfObfuscatedPublisherFontOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use a device font at runtime; no third-party font is redistributed as a fixture.
        val font = File("/system/fonts").listFiles().orEmpty().first { it.extension == "ttf" }.readBytes()
        val key = MessageDigest.getInstance("SHA-1").digest("abc".toByteArray())
        val encoded = font.mapIndexed { i, byte -> if (i < 1040) (byte.toInt() xor key[i % 20].toInt()).toByte() else byte }.toByteArray()
        val entries = mapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray(),
            "book.opf" to """<package unique-identifier="uid"><metadata><identifier id="uid">abc</identifier></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="f" href="font.ttf" media-type="font/ttf"/></manifest><spine><itemref idref="c"/></spine></package>""".toByteArray(),
            "META-INF/encryption.xml" to """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><CipherData><CipherReference URI="font.ttf"/></CipherData></EncryptedData></encryption>""".toByteArray(),
            "chapter.xhtml" to """<html><head><style>@font-face{font-family:LumbreObfuscated;src:url('font.ttf')}p{font-family:LumbreObfuscated}</style></head><body><p>Publisher font test.</p></body></html>""".toByteArray(),
            "font.ttf" to encoded
        )
        val file = File.createTempFile("font-render-", ".epub", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
            val publication = EpubParser().parse(file, "Font test")
            val book = OpenBook(BookEntity("font-test", "Font test", null, "test", "test", null, null, null, 1), publication, file)
            val finished = CountDownLatch(1)
            val result = AtomicReference<String>()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val web = WebView(activity)
                    activity.setContentView(web)
                    web.settings.javaScriptEnabled = true
                    web.settings.blockNetworkLoads = true
                    val preferences = ReaderPreferences(font = "publisher")
                    val client = BookWebViewClient(book, preferences, EpubContent.Colors("#fff", "#000", "#00f", "#ccc", false), 1f,
                        onLink = { _, _ -> }, onReady = { view ->
                            view.evaluateJavascript("document.fonts.load('20px LumbreObfuscated').then(function(fonts){window.fontResult=fonts.length>0&&fonts.every(function(f){return f.status==='loaded'})?'loaded':'missing'}).catch(function(){window.fontResult='failed'})", null)
                            fun poll() {
                                view.evaluateJavascript("window.fontResult||'pending'") {
                                    if (it == "\"pending\"") view.postDelayed({ poll() }, 100)
                                    else { result.set(it); finished.countDown() }
                                }
                            }
                            poll()
                        }, onError = { result.set("error"); finished.countDown() })
                    web.webViewClient = client
                    web.loadUrl(client.url("chapter.xhtml"))
                }
                assertTrue(finished.await(20, TimeUnit.SECONDS))
                assertEquals("\"loaded\"", result.get())
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    (root.getChildAt(0) as WebView).destroy(); root.removeAllViews()
                }
            }
        } finally { file.delete() }
    }
}
