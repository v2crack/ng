package com.v2ray.ang.util

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field

/**
 * Resolves the user-selected app font and installs it app-wide by replacing
 * the static Typeface defaults (DEFAULT / SANS_SERIF / …).
 *
 * Must be called once at process start (Application.onCreate) after MMKV init.
 * Changing the preference requires an app restart to take full effect.
 */
object AppFontHelper {

    const val FONT_RUSSO_ONE = "russoone"
    const val FONT_GOOGLE_SANS = "google_sans"
    const val FONT_SYSTEM = "system"
    const val FONT_CUSTOM = "custom"

    private const val CUSTOM_FONT_DIR = "fonts"
    private const val CUSTOM_FONT_FILE = "user_custom_font"

    @Volatile
    private var appliedToken: String? = null

    fun currentFontKey(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_FONT, FONT_RUSSO_ONE)
            ?: FONT_RUSSO_ONE
    }

    fun displayName(key: String, customFileName: String? = null): String {
        val name = when (key) {
            FONT_RUSSO_ONE -> "Russo One (текущий)"
            FONT_GOOGLE_SANS -> "Google Sans"
            FONT_SYSTEM -> "Системный"
            FONT_CUSTOM -> customFileName?.takeIf { it.isNotBlank() }?.let { "Свой: $it" }
                ?: "Свой шрифт"
            else -> key
        }
        return "$name\nТребуется перезапуск"
    }

    /**
     * Loads the selected font and replaces system default Typefaces so that
     * newly inflated layouts (and most TextViews) use it automatically.
     */
    fun applyAppWide(context: Context) {
        val key = currentFontKey()
        val customPath = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_FONT_PATH).orEmpty()
        val token = "$key|$customPath"
        if (appliedToken == token) return

        val typeface = resolveTypeface(context, key, customPath) ?: run {
            // System font: leave platform defaults alone
            if (key == FONT_SYSTEM) {
                appliedToken = token
            }
            return
        }

        try {
            replaceDefaultTypefaces(typeface)
            appliedToken = token
            LogUtil.i(AppConfig.TAG, "App font applied: $key")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to apply app font", e)
        }
    }

    fun invalidateCache() {
        appliedToken = null
    }

    /**
     * Copies a user-selected font into app-private storage and returns the absolute path.
     * Accepts .ttf / .otf (and similar). Does not apply live — restart required.
     */
    fun importCustomFont(context: Context, uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, CUSTOM_FONT_DIR).apply { mkdirs() }
            val nameHint = queryDisplayName(context, uri) ?: "font.ttf"
            val ext = nameHint.substringAfterLast('.', "ttf").lowercase()
                .takeIf { it in setOf("ttf", "otf", "ttc", "otc") } ?: "ttf"
            val outFile = File(dir, "$CUSTOM_FONT_FILE.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            } ?: return null

            // Validate by trying to load
            val tf = Typeface.createFromFile(outFile)
            if (tf == null) {
                outFile.delete()
                return null
            }

            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_FONT_PATH, outFile.absolutePath)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_FONT_NAME, nameHint)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_FONT, FONT_CUSTOM)
            invalidateCache()
            outFile.absolutePath
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import custom font", e)
            null
        }
    }

    private fun resolveTypeface(context: Context, key: String, customPath: String): Typeface? {
        return when (key) {
            FONT_SYSTEM -> null
            FONT_GOOGLE_SANS -> ResourcesCompat.getFont(context, R.font.google_sans)
                ?: ResourcesCompat.getFont(context, R.font.google_sans_regular)
            FONT_CUSTOM -> loadCustomTypeface(customPath)
            else -> ResourcesCompat.getFont(context, R.font.russoone)
        }
    }

    /**
     * Reflectively replace Typeface static defaults so inflation uses our font.
     * Uses only public static fields to stay compatible with API 37+ restricted APIs.
     */
    private fun replaceDefaultTypefaces(typeface: Typeface) {
        setStaticTypefaceField("DEFAULT", typeface)
        setStaticTypefaceField("DEFAULT_BOLD", Typeface.create(typeface, Typeface.BOLD))
        setStaticTypefaceField("SANS_SERIF", typeface)
        setStaticTypefaceField("SERIF", typeface)
        setStaticTypefaceField("MONOSPACE", typeface)

        // Best-effort: system font map (may be blocked on newer APIs / OEMs)
        try {
            @Suppress("DiscouragedPrivateApi")
            val field = Typeface::class.java.getDeclaredField("sSystemFontMap")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(null) as? MutableMap<String, Typeface>
            if (map != null) {
                for (k in map.keys.toList()) {
                    map[k] = typeface
                }
            }
        } catch (_: Throwable) {
            // ignore — public DEFAULT/SANS_SERIF override is enough for most layouts
        }
    }

    private fun setStaticTypefaceField(name: String, value: Typeface) {
        try {
            val field: Field = Typeface::class.java.getDeclaredField(name)
            field.isAccessible = true

            // Remove FINAL modifier if present (pre-Java 12 / older Android)
            try {
                val modifiersField = Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
            } catch (_: Exception) {
                // On newer runtimes FINAL may not be strip-able; set() may still work for statics
            }

            field.set(null, value)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to set Typeface.$name", e)
        }
    }

    private fun loadCustomTypeface(path: String): Typeface? {
        if (path.isBlank()) return null
        return try {
            val file = File(path)
            if (!file.exists()) null else Typeface.createFromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
