package com.pckeyboard.ime.remote

import androidx.core.content.FileProvider

/** Clears AndroidX's static roots because Robolectric gives each test a new private cache path. */
internal fun resetDiagnosticFileProviderCache() {
    val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    val cache = field.get(null) as MutableMap<*, *>
    synchronized(cache) { cache.clear() }
}
