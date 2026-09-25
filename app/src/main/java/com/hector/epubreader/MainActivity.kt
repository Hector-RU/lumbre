package com.hector.epubreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.hector.epubreader.ui.ReaderApp

class MainActivity : AppCompatActivity() {
    var onReaderVolumeKey: ((Int) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
            if (onReaderVolumeKey != null) {
                if (event.action == KeyEvent.ACTION_DOWN) onReaderVolumeKey?.invoke(direction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"))
        }
        enableEdgeToEdge()
        setContent { ReaderApp() }
    }
}
