package com.master.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var saveButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var wakeWordButton: Button
    private lateinit var permissionsStatus: TextView

    private val recordAudioPermissionCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Dynamic layout creation so the user doesn't need complex XML layouts setup initially
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val titleView = TextView(this).apply {
            text = "Мастер: Автономный ИИ Ассистент"
            textSize = 24f
            setPadding(0, 20, 0, 50)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        container.addView(titleView)

        apiKeyInput = EditText(this).apply {
            hint = "Введите ваш Gemini API Key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(apiKeyInput)

        saveButton = Button(this).apply {
            text = "Сохранить API Key"
        }
        container.addView(saveButton)

        accessibilityButton = Button(this).apply {
            text = "Включить Специальные Возможности"
            setPadding(0, 30, 0, 30)
        }
        container.addView(accessibilityButton)

        wakeWordButton = Button(this).apply {
            text = "Запустить Голосовой Модуль"
        }
        container.addView(wakeWordButton)

        permissionsStatus = TextView(this).apply {
            text = "Статус разрешений:\nСпец. Возможности: Выкл\nМикрофон: Выкл"
            setPadding(0, 40, 0, 0)
        }
        container.addView(permissionsStatus)

        setContentView(container)

        // Load saved API Key
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        val savedKey = sharedPref.getString("GEMINI_API_KEY", "")
        apiKeyInput.setText(savedKey)

        saveButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                with(sharedPref.edit()) {
                    putString("GEMINI_API_KEY", key)
                    apply()
                }
                Toast.makeText(this, "API Key сохранен!", Toast.LENGTH_SHORT).show()
            }
        }

        accessibilityButton.setOnClickListener {
            // Open System accessibility services menu to toggle on "Master"
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        wakeWordButton.setOnClickListener {
            val key = sharedPref.getString("GEMINI_API_KEY", "") ?: ""
            if (key.isEmpty()) {
                Toast.makeText(this, "Пожалуйста, сначала сохраните API Key!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (checkMicPermission()) {
                startService(Intent(this, WakeWordService::class.java).apply {
                    putExtra("API_KEY", key)
                })
                Toast.makeText(this, "Служба Мастера запущена!", Toast.LENGTH_SHORT).show()
            } else {
                requestMicPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled(this, MasterAccessibilityService::class.java)
        val isMicEnabled = checkMicPermission()

        permissionsStatus.text = "Статус разрешений:\n" +
                "Спец. Возможности: ${if (isAccessibilityEnabled) "ВКЛЮЧЕНЫ" else "ВЫКЛЮЧЕНЫ"}\n" +
                "Микрофон: ${if (isMicEnabled) "РАЗРЕШЕН" else "ТРЕБУЕТСЯ РАЗРЕШЕНИЕ"}"
    }

    private fun checkMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioPermissionCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == recordAudioPermissionCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            updatePermissionStatus()
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val expectedComponentName = android.content.ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }
}
