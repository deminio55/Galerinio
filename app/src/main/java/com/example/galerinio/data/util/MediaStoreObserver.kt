package com.example.galerinio.data.util

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

class MediaStoreObserver(
    private val contentResolver: ContentResolver,
    private val observedUris: List<Uri>,
    private val onChanged: () -> Unit
) {
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onChanged()
        }
    }

    fun register() {
        observedUris.forEach { uri ->
            contentResolver.registerContentObserver(uri, true, observer)
        }
    }

    fun unregister() {
        contentResolver.unregisterContentObserver(observer)
    }
}

