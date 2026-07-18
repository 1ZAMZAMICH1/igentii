package com.master.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var providerSpinner: Spinner
    private lateinit var apiKeyInput: EditText
    private lateinit var apiUrlInput: EditText
    private lateinit var modelNameInput: EditText
    private lateinit var urlLabel: TextView
    private lateinit var modelLabel: TextView
    private lateinit var saveButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var wakeWordButton: Button
    private lateinit var permissionsStatus: TextView

    private val recordAudioPermissionCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val titleView = TextView(this).apply {
            text = "Мастер: ИИ Ассистент"
            textSize = 24f
            setPadding(0, 10, 0, 30)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        container.addView(titleView)

        val spinnerLabel = TextView(this).apply {
            text = "Выберите ИИ Провайдера:"
            textSize = 16f
            setPadding(0, 10, 0, 10)
        }
        container.addView(spinnerLabel)

        providerSpinner = Spinner(this).apply {
            setPadding(10, 20, 10, 20)
        }
        container.addView(providerSpinner)

        apiKeyInput = EditText(this).apply {
            hint = "Введите API Key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(apiKeyInput)

        urlLabel = TextView(this).apply {
            text = "API Base URL:"
            visibility = View.GONE
        }
        container.addView(urlLabel)

        apiUrlInput = EditText(this).apply {
            hint = "API URL"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            visibility = View.GONE
        }
        container.addView(apiUrlInput)

        modelLabel = TextView(this).apply {
            text = "Имя модели:"
            visibility = View.GONE
        }
        container.addView(modelLabel)

        modelNameInput = EditText(this).apply {
            hint = "Model Name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            visibility = View.GONE
        }
        container.addView(modelNameInput)

        saveButton = Button(this).apply {
            text = "Сохранить настройки"
        }
        container.addView(saveButton)

        accessibilityButton = Button(this).apply {
            text = "Включить Специальные Возможности"
            setPadding(0, 20, 0, 20)
        }
        container.addView(accessibilityButton)

        wakeWordButton = Button(this).apply {
            text = "Запустить Голосовой Модуль"
        }
        container.addView(wakeWordButton)

        permissionsStatus = TextView(this).apply {
            text = "Статус разрешений:\nСпец. Возможности: Выкл\nМикрофон: Выкл"
            setPadding(0, 30, 0, 0)
        }
        container.addView(permissionsStatus)

        setContentView(container)

        // Инициализация Спиннера
        val providers = arrayOf("Google Gemini", "Hugging Face (Llama 3)", "DeepSeek", "Custom / Другой")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        providerSpinner.adapter = adapter

        val sharedPref = getPreferences(Context.MODE_PRIVATE)

        // Загружаем сохраненные данные
        val savedProviderIndex = sharedPref.getInt("PROVIDER_INDEX", 0)
        val savedKey = sharedPref.getString("GEMINI_API_KEY", "")
        val savedUrl = sharedPref.getString("API_URL", "https://generativelanguage.googleapis.com")
        val savedModel = sharedPref.getString("MODEL_NAME", "gemini-1.5-flash")

        providerSpinner.setSelection(savedProviderIndex)
        apiKeyInput.setText(savedKey)
        apiUrlInput.setText(savedUrl)
        modelNameInput.setText(savedModel)

        // Обработка выбора провайдера в Spinner
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { // Gemini
                        apiUrlInput.setText("https://generativelanguage.googleapis.com")
                        modelNameInput.setText("gemini-1.5-flash")
                        apiKeyInput.visibility = View.VISIBLE
                        setCustomFieldsVisible(false)
                    }
                    1 -> { // Hugging Face
                        apiUrlInput.setText("https://router.huggingface.co/v1")
                        modelNameInput.setText("meta-llama/Meta-Llama-3-8B-Instruct")
                        apiKeyInput.visibility = View.VISIBLE
                        setCustomFieldsVisible(false)
                    }
                    2 -> { // DeepSeek
                        apiUrlInput.setText("https://api.deepseek.com/chat/completions")
                        modelNameInput.setText("deepseek-chat")
                        apiKeyInput.visibility = View.VISIBLE
                        setCustomFieldsVisible(false)
                    }
                    3 -> { // Custom
                        setCustomFieldsVisible(true)
                        apiKeyInput.visibility = View.GONE // API Key не требуется для локального
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        saveButton.setOnClickListener {
            val position = providerSpinner.selectedItemPosition
            val key = apiKeyInput.text.toString().trim()
            val url = apiUrlInput.text.toString().trim()
            val model = modelNameInput.text.toString().trim()

            with(sharedPref.edit()) {
                putInt("PROVIDER_INDEX", position)
                putString("GEMINI_API_KEY", key)
                putString("API_URL", url)
                putString("MODEL_NAME", model)
                apply()
            }
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
        }

        accessibilityButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        wakeWordButton.setOnClickListener {
            val position = providerSpinner.selectedItemPosition
            val key = sharedPref.getString("GEMINI_API_KEY", "") ?: ""
            val url = sharedPref.getString("API_URL", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com"
            val model = sharedPref.getString("MODEL_NAME", "gemini-1.5-flash") ?: "gemini-1.5-flash"

            if (key.isEmpty() && position != 3) { // API Key требуется для облачных провайдеров
                Toast.makeText(this, "Пожалуйста, введите API Key!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (checkMicPermission()) {
                startService(Intent(this, WakeWordService::class.java).apply {
                    putExtra("API_KEY", key)
                    putExtra("API_URL", url)
                    putExtra("MODEL_NAME", model)
                })
                Toast.makeText(this, "Служба Мастера запущена!", Toast.LENGTH_SHORT).show()
            } else {
                requestMicPermission()
            }
        }
    }

    private fun setCustomFieldsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        urlLabel.visibility = visibility
        apiUrlInput.visibility = visibility
        modelLabel.visibility = visibility
        modelNameInput.visibility = visibility
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