package com.v2ray.ang

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.MainActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

class AngApplication : MultiDexApplication() {
companion object {
    lateinit var application: AngApplication
    private val subscriptionListeners = mutableListOf<SubscriptionUpdateListener>()

    fun addSubscriptionListener(listener: SubscriptionUpdateListener) {
        if (!subscriptionListeners.contains(listener)) {
            subscriptionListeners.add(listener)
        }
    }

    fun removeSubscriptionListener(listener: SubscriptionUpdateListener) {
        subscriptionListeners.remove(listener)
    }

    private fun notifySubscriptionUpdated() {
        subscriptionListeners.forEach { it.onSubscriptionUpdated() }
    }
}

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)
        WorkManager.initialize(this, workManagerConfiguration)
        SettingsManager.initApp(this)
        SettingsManager.setNightMode()

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 300)
            .apply()
        createPermanentGroup()
        
        // Проверка обновлений
        checkAppVersion()
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun checkAppVersion() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("AngApplication", "checkAppVersion() STARTED")
                
                // Собираем данные
                val androidId = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "unavailable"

                val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                
                val language = Locale.getDefault().language
                val country = Locale.getDefault().country
                val region = "$language-$country"
                
                val appVersion = getAppVersion()

                // Формируем JSON
                val json = """
                    {
                        "android_id": "$androidId",
                        "android_version": "$androidVersion",
                        "region": "$region",
                        "app_version": "$appVersion"
                    }
                """.trimIndent()

                android.util.Log.d("AngApplication", "Sending: $json")

                val url = java.net.URL("https://junify.ru/api/check")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.outputStream.use { os ->
                    val input = json.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                android.util.Log.d("AngApplication", "Response code: $responseCode")
                
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    android.util.Log.d("AngApplication", "Server response: $response")
                    
                    val jsonResponse = JSONObject(response)
                    val type = jsonResponse.optString("type")
                    
                    if (type == "outdated") {
                        val updateUrl = jsonResponse.optString("url")
                        val message = jsonResponse.optString("message", "Доступно важное обновление!")
                        
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(message, updateUrl)
                        }
                    }
                }
                
                connection.disconnect()

            } catch (e: Exception) {
                android.util.Log.e("AngApplication", "Failed: ${e.message}", e)
            }
        }
    }

    private fun showUpdateDialog(message: String, url: String) {
        try {
            val dialog = AlertDialog.Builder(this)
                .setTitle("⚠️ Обновление")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Обновить") { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.e("AngApplication", "Failed to open URL: ${e.message}")
                    }
                }
                .setNegativeButton("Выход") { _, _ ->
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .create()
            
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("AngApplication", "Dialog error: ${e.message}")
        }
    }

private fun createPermanentGroup() {
    val permanentGroupId = "permanent_junify"
    val subs = MmkvManager.decodeSubscriptions()

    if (!subs.any { it.guid == permanentGroupId }) {
        val group = SubscriptionItem(
            remarks = "✨ 𝘾𝙤𝙢𝙢𝙪𝙣𝙞𝙩𝙮 ✨",
            url = "https://junify.ru/subs",
            enabled = true,
            autoUpdate = true,
            updateInterval = 360,
            isPermanent = true
        )
        MmkvManager.encodeSubscription(permanentGroupId, group)
        
        // 👇 ДОБАВИТЬ ЭТОТ БЛОК - сразу обновляем подписку после добавления
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("AngApplication", "Auto-updating subscription after creation")
                // Используем AngConfigManager для обновления
                val result = com.v2ray.ang.handler.AngConfigManager.updateConfigViaSub(
                    com.v2ray.ang.dto.SubscriptionCache(permanentGroupId, group)
                )
withContext(Dispatchers.Main) {
    android.util.Log.d("AngApplication", "Subscription updated: ${result.configCount} configs")
    // Уведомляем всех слушателей
    notifySubscriptionUpdated()
}
            } catch (e: Exception) {
                android.util.Log.e("AngApplication", "Failed to auto-update subscription", e)
            }
        }
    }
}
}
