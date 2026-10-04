package com.pckeyboard.ime.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.preference.PreferenceManager

/**
 * Persists the selected theme and any user-built custom themes.
 *
 * Storage layout:
 *  - "selected_theme_id"   : id of the active theme
 *  - "custom_theme_ids"    : comma-separated list of saved custom theme ids
 *  - "theme_<id>_<field>"  : individual field of a custom theme
 *
 * Lives in **device-protected storage** so the user's theme is honoured
 * on the lock screen too — see KeyboardPrefs for the rationale and the
 * one-time CE→DE migration that brings legacy installs forward (theme
 * keys are migrated by KeyboardPrefs since they're not in its
 * filter-out list).
 */
class ThemeRepository(context: Context) {

    private val appContext = context.applicationContext
    // Resources follow the caller's configuration without retaining its Activity or Service.
    private val resources = context.resources

    private val storeContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext()
        } else {
            appContext
        }

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(storeContext)

    /** Follows system appearance without overwriting the user's manually selected theme. */
    var followSystemTheme: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SYSTEM, false)
        set(value) { prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM, value).apply() }

    /** Resolves automatic appearance from the owning display context, or restores manual choice. */
    fun getSelectedTheme(): KeyboardTheme {
        if (followSystemTheme) {
            return if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                Themes.DARK
            } else {
                Themes.LIGHT
            }
        }
        val id = prefs.getString(KEY_SELECTED, Themes.LIGHT.id) ?: Themes.LIGHT.id
        return getThemeById(id) ?: Themes.LIGHT
    }

    /** Makes an explicit theme selection authoritative and exits automatic appearance. */
    fun selectTheme(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply {
            if (followSystemTheme) putBoolean(KEY_FOLLOW_SYSTEM, false)
        }.apply()
    }

    /** Keeps visible keyboard chrome in sync and returns a strongly retained unsubscribe action. */
    fun observeSelectedTheme(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val selected = prefs.getString(KEY_SELECTED, Themes.LIGHT.id)
            if (key == null || key == KEY_SELECTED || key == KEY_FOLLOW_SYSTEM || key.startsWith("$KEY_PREFIX${selected}_")) {
                onChange()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getThemeById(id: String): KeyboardTheme? {
        val builtIn = Themes.builtIn.firstOrNull { it.id == id }
        if (builtIn != null) return builtIn
        return loadCustom(id)
    }

    fun listCustomThemes(): List<KeyboardTheme> =
        readCustomIds().mapNotNull { loadCustom(it) }

    fun saveCustomTheme(theme: KeyboardTheme) {
        val ids = readCustomIds().toMutableSet()
        ids.add(theme.id)
        prefs.edit()
            .putStringSet(KEY_CUSTOM_IDS, ids)
            .apply()
        val editor = prefs.edit()
        theme.toMap().forEach { (k, v) ->
            editor.putString("$KEY_PREFIX${theme.id}_$k", v)
        }
        editor.apply()
    }

    fun deleteCustomTheme(id: String) {
        val ids = readCustomIds().toMutableSet()
        if (!ids.remove(id)) return
        val editor = prefs.edit().putStringSet(KEY_CUSTOM_IDS, ids)
        for (k in prefs.all.keys.filter { it.startsWith("$KEY_PREFIX${id}_") }) {
            editor.remove(k)
        }
        editor.apply()
        if (prefs.getString(KEY_SELECTED, "") == id) {
            prefs.edit().putString(KEY_SELECTED, Themes.LIGHT.id).apply()
        }
    }

    fun allThemes(): List<KeyboardTheme> = Themes.builtIn + listCustomThemes()

    private fun loadCustom(id: String): KeyboardTheme? {
        val map = mutableMapOf<String, String>()
        for (k in prefs.all.keys.filter { it.startsWith("$KEY_PREFIX${id}_") }) {
            val field = k.removePrefix("$KEY_PREFIX${id}_")
            val value = prefs.getString(k, null) ?: continue
            map[field] = value
        }
        if (map.isEmpty()) return null
        if (!map.containsKey("id")) map["id"] = id
        return KeyboardTheme.fromMap(map)
    }

    private fun readCustomIds(): Set<String> =
        prefs.getStringSet(KEY_CUSTOM_IDS, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_SELECTED = "selected_theme_id"
        private const val KEY_FOLLOW_SYSTEM = "follow_system_theme"
        private const val KEY_CUSTOM_IDS = "custom_theme_ids"
        private const val KEY_PREFIX = "theme_"
    }
}
