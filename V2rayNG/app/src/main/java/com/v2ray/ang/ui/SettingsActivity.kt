package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MmkvPreferenceDataStore
import com.v2ray.ang.util.AppFontHelper
import com.v2ray.ang.util.Utils
import java.util.concurrent.TimeUnit

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_settings, showHomeAsUp = true, title = getString(R.string.title_settings))
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var pickMediaLauncher: ActivityResultLauncher<Array<String>>
        private lateinit var pickFontLauncher: ActivityResultLauncher<Array<String>>

        private val localDns by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_LOCAL_DNS_ENABLED) }
        private val fakeDns by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_FAKE_DNS_ENABLED) }
        private val appendHttpProxy by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_APPEND_HTTP_PROXY) }

        private val vpnDns by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_DNS) }
        private val vpnBypassLan by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_BYPASS_LAN) }
        private val vpnInterfaceAddress by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX) }
        private val vpnMtu by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_MTU) }

        private val mux by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_MUX_ENABLED) }
        private val muxConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_CONCURRENCY) }
        private val muxXudpConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_XUDP_CONCURRENCY) }
        private val muxXudpQuic by lazy { findPreference<ListPreference>(AppConfig.PREF_MUX_XUDP_QUIC) }

        private val fragment by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_FRAGMENT_ENABLED) }
        private val fragmentPackets by lazy { findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS) }
        private val fragmentLength by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH) }
        private val fragmentInterval by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL) }

        private val autoUpdateCheck by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.SUBSCRIPTION_AUTO_UPDATE) }
        private val autoUpdateInterval by lazy { findPreference<EditTextPreference>(AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL) }
        private val mode by lazy { findPreference<ListPreference>(AppConfig.PREF_MODE) }
        private val socksPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PORT) }
        private val dynamicSocksPort by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_DYNAMIC_SOCKS_PORT) }

        private val hevTunLogLevel by lazy { findPreference<ListPreference>(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) }
        private val hevTunRwTimeout by lazy { findPreference<EditTextPreference>(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) }
        private val useHevTun by lazy { findPreference<SwitchPreferenceCompat>(AppConfig.PREF_USE_HEV_TUNNEL) }

        private val fontMimeTypes = arrayOf(
            "font/ttf",
            "font/otf",
            "application/x-font-ttf",
            "application/x-font-otf",
            "application/octet-stream",
            "*/*"
        )

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            addPreferencesFromResource(R.xml.pref_settings)

            // Инициализация лаунчера после привязки к Activity
            pickMediaLauncher = registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { picked ->
                    val mime = try {
                        requireActivity().contentResolver.getType(picked)
                    } catch (_: Exception) {
                        null
                    }
                    if (mime?.startsWith("video") == true) {
                        // Video: keep content URI (no pre-blur)
                        try {
                            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            requireActivity().contentResolver.takePersistableUriPermission(picked, takeFlags)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_BACKGROUND_URI, picked.toString())
                        findPreference<Preference>(AppConfig.PREF_CUSTOM_BACKGROUND_PICK)?.summary =
                            "Видео (без размытия)"
                        requireContext().toast("Видео-фон сохранён")
                    } else {
                        // Image: copy into app files, then bake blur if needed
                        val path = com.v2ray.ang.util.BackgroundBlurHelper.importOriginalImage(
                            requireContext(),
                            picked
                        )
                        if (path != null) {
                            val level = MmkvManager.decodeSettingsInt(
                                AppConfig.PREF_CUSTOM_BACKGROUND_BLUR,
                                0
                            )
                            // Pre-render blur off UI thread
                            Thread {
                                com.v2ray.ang.util.BackgroundBlurHelper.ensureDisplayImage(
                                    requireContext().applicationContext,
                                    level
                                )
                            }.start()
                            findPreference<Preference>(AppConfig.PREF_CUSTOM_BACKGROUND_PICK)?.summary =
                                "Фото сохранено в приложении"
                            requireContext().toast("Фон сохранён")
                        } else {
                            requireContext().toastError("Не удалось сохранить изображение")
                        }
                    }
                }
            }

            val pickBgPref = findPreference<Preference>(AppConfig.PREF_CUSTOM_BACKGROUND_PICK)
            pickBgPref?.setOnPreferenceClickListener {
                pickMediaLauncher.launch(arrayOf("image/*", "video/*"))
                true
            }
            val storedBg = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_BACKGROUND_URI)
            pickBgPref?.summary = when {
                storedBg.isNullOrBlank() -> "Нажмите, чтобы выбрать файл"
                storedBg.startsWith("content://") -> "Видео / внешний файл"
                else -> "Фото сохранено в приложении"
            }

            val blurBgPref = findPreference<SeekBarPreference>(AppConfig.PREF_CUSTOM_BACKGROUND_BLUR)
            blurBgPref?.setOnPreferenceChangeListener { preference, newValue ->
                val level = (newValue as? Int) ?: 0
                preference.summary = blurSummary(level)
                // Bake blur into a file once (not realtime)
                Thread {
                    com.v2ray.ang.util.BackgroundBlurHelper.ensureDisplayImage(
                        requireContext().applicationContext,
                        level
                    )
                }.start()
                requireContext().toast("Размытие $level% — применится на главном экране")
                true
            }
            blurBgPref?.summary = blurSummary(
                MmkvManager.decodeSettingsInt(AppConfig.PREF_CUSTOM_BACKGROUND_BLUR, 0)
            )

            val customBgEnabled = findPreference<SwitchPreferenceCompat>(AppConfig.PREF_CUSTOM_BACKGROUND_ENABLED)
            fun updateCustomBgPrefsEnabled(enabled: Boolean) {
                pickBgPref?.isEnabled = enabled
                blurBgPref?.isEnabled = enabled
            }
            updateCustomBgPrefsEnabled(
                MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_BACKGROUND_ENABLED, false)
            )
            customBgEnabled?.setOnPreferenceChangeListener { _, newValue ->
                updateCustomBgPrefsEnabled(newValue as Boolean)
                true
            }

            // Theme picker disabled — force dark
            findPreference<ListPreference>(AppConfig.PREF_UI_MODE_NIGHT)?.apply {
                isEnabled = false
                isSelectable = false
                value = "2"
                summary = "Временно недоступно — используется тёмная тема"
            }

            setupCustomFontPreferences()

            initPreferenceSummaries()

            localDns?.setOnPreferenceChangeListener { _, any ->
                updateLocalDns(any as Boolean)
                true
            }

            mux?.setOnPreferenceChangeListener { _, newValue ->
                updateMux(newValue as Boolean)
                true
            }
            muxConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxConcurrency(newValue as String)
                true
            }
            muxXudpConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxXudpConcurrency(newValue as String)
                true
            }

            fragment?.setOnPreferenceChangeListener { _, newValue ->
                updateFragment(newValue as Boolean)
                true
            }

            autoUpdateCheck?.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as Boolean
                autoUpdateCheck?.isChecked = value
                autoUpdateInterval?.isEnabled = value
                autoUpdateInterval?.text?.toLongEx()?.let {
                    if (newValue) configureUpdateTask(it) else cancelUpdateTask()
                }
                true
            }

            mode?.setOnPreferenceChangeListener { pref, newValue ->
                val valueStr = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(valueStr)
                    lp.summary = if (idx >= 0) lp.entries[idx] else valueStr
                }
                updateMode(valueStr)
                true
            }
            mode?.dialogLayoutResource = R.layout.preference_with_help_link

            useHevTun?.setOnPreferenceChangeListener { _, newValue ->
                updateHevTunSettings(newValue as Boolean)
                true
            }

            dynamicSocksPort?.setOnPreferenceChangeListener { _, newValue ->
                updateDynamicSocksPort(newValue as Boolean)
                true
            }
        }

        private fun initPreferenceSummaries() {
            fun updateSummary(pref: Preference) {
                when (pref) {
                    is EditTextPreference -> {
                        if (pref.key == AppConfig.PREF_SOCKS_PASSWORD) {
                            pref.summary = if (pref.text.isNullOrEmpty()) "" else "******"
                        } else {
                            pref.summary = pref.text.orEmpty()
                        }
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            if (p.key == AppConfig.PREF_SOCKS_PASSWORD) {
                                p.summary = if ((newValue as? String).isNullOrEmpty()) "" else "******"
                            } else {
                                p.summary = (newValue as? String).orEmpty()
                            }
                            true
                        }
                    }

                    is ListPreference -> {
                        // Custom font has its own change listener / summary
                        if (pref.key == AppConfig.PREF_CUSTOM_FONT) return
                        pref.summary = pref.entry ?: ""
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            val lp = p as ListPreference
                            val idx = lp.findIndexOfValue(newValue as? String)
                            lp.summary = (if (idx >= 0) lp.entries[idx] else newValue) as CharSequence?
                            true
                        }
                    }

                    is SwitchPreferenceCompat -> {
                    }
                }
            }

            fun traverse(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val p = group.getPreference(i)) {
                        is androidx.preference.PreferenceGroup -> traverse(p)
                        else -> updateSummary(p)
                    }
                }
            }

            preferenceScreen?.let { traverse(it) }
        }

        override fun onStart() {
            super.onStart()
            updateHevTunSettings(MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true))
            updateMode(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN))
            updateMux(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false))
            updateFragment(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))
            autoUpdateInterval?.isEnabled = MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, false)
            updateDynamicSocksPort(MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false))
        }

        private fun updateMode(value: String?) {
            val vpn = value == VPN
            localDns?.isEnabled = vpn
            fakeDns?.isEnabled = vpn
            appendHttpProxy?.isEnabled = vpn
            vpnDns?.isEnabled = vpn
            vpnBypassLan?.isEnabled = vpn
            vpnInterfaceAddress?.isEnabled = vpn
            vpnMtu?.isEnabled = vpn
            useHevTun?.isEnabled = vpn
            updateHevTunSettings(false)
            if (vpn) {
                updateLocalDns(MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false))
                updateHevTunSettings(MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, false))
            }
        }

        private fun updateLocalDns(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
            vpnDns?.isEnabled = !enabled
        }

        private fun configureUpdateTask(interval: Long) {
            val rw = RemoteWorkManager.getInstance(AngApplication.application)
            rw.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            rw.enqueueUniquePeriodicWork(
                AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(
                    SubscriptionUpdater.UpdateTask::class.java,
                    interval,
                    TimeUnit.MINUTES
                ).apply {
                    setInitialDelay(interval, TimeUnit.MINUTES)
                }.build()
            )
        }

        private fun cancelUpdateTask() {
            val rw = RemoteWorkManager.getInstance(AngApplication.application)
            rw.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
        }

        private fun updateMux(enabled: Boolean) {
            muxConcurrency?.isEnabled = enabled
            muxXudpConcurrency?.isEnabled = enabled
            muxXudpQuic?.isEnabled = enabled
            if (enabled) {
                updateMuxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"))
                updateMuxXudpConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"))
            }
        }

        private fun updateMuxConcurrency(value: String?) {
            val concurrency = value?.toIntOrNull() ?: 8
            muxConcurrency?.summary = concurrency.toString()
        }

        private fun updateMuxXudpConcurrency(value: String?) {
            if (value == null) {
                muxXudpQuic?.isEnabled = true
            } else {
                val concurrency = value.toIntOrNull() ?: 8
                muxXudpConcurrency?.summary = concurrency.toString()
                muxXudpQuic?.isEnabled = concurrency >= 0
            }
        }

        private fun updateFragment(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
        }

        private fun updateDynamicSocksPort(enabled: Boolean) {
            socksPort?.isEnabled = !enabled
        }

        private fun updateHevTunSettings(enabled: Boolean) {
            hevTunLogLevel?.isEnabled = enabled
            hevTunRwTimeout?.isEnabled = enabled
        }

        private fun blurSummary(level: Int): String {
            val value = level.coerceIn(0, 100)
            return if (value == 0) {
                "Без размытия"
            } else {
                "Уровень: $value"
            }
        }

        private fun setupCustomFontPreferences() {
            val fontPref = findPreference<ListPreference>(AppConfig.PREF_CUSTOM_FONT)
            val pickFontPref = findPreference<Preference>(AppConfig.PREF_CUSTOM_FONT_PICK)

            pickFontLauncher = registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) return@registerForActivityResult
                val path = AppFontHelper.importCustomFont(requireContext(), uri)
                if (path != null) {
                    val name = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_FONT_NAME)
                    fontPref?.value = AppFontHelper.FONT_CUSTOM
                    updateFontSummaries()
                    requireContext().toast("Шрифт сохранён: ${name ?: "custom"}. Перезапустите приложение.")
                } else {
                    requireContext().toastError("Не удалось загрузить шрифт (.ttf / .otf)")
                }
            }

            pickFontPref?.setOnPreferenceClickListener {
                pickFontLauncher.launch(fontMimeTypes)
                true
            }

            fontPref?.setOnPreferenceChangeListener { _, newValue ->
                val key = newValue as? String ?: AppFontHelper.FONT_RUSSO_ONE
                if (key == AppFontHelper.FONT_CUSTOM) {
                    val path = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_FONT_PATH)
                    if (path.isNullOrBlank()) {
                        pickFontLauncher.launch(fontMimeTypes)
                        return@setOnPreferenceChangeListener false
                    }
                }
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_FONT, key)
                AppFontHelper.invalidateCache()
                updateFontSummaries()
                requireContext().toast("Шрифт сохранён. Перезапустите приложение.")
                true
            }

            updateFontSummaries()
        }

        private fun updateFontSummaries() {
            val fontPref = findPreference<ListPreference>(AppConfig.PREF_CUSTOM_FONT)
            val pickFontPref = findPreference<Preference>(AppConfig.PREF_CUSTOM_FONT_PICK)
            val key = AppFontHelper.currentFontKey()
            val customName = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_FONT_NAME)

            fontPref?.summary = AppFontHelper.displayName(key, customName)
            pickFontPref?.summary = if (!customName.isNullOrBlank()) {
                "Загружен: $customName\nТребуется перезапуск"
            } else {
                "Файл .ttf / .otf\nТребуется перезапуск"
            }
        }
    }

    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.APP_WIKI_MODE)
    }
}
