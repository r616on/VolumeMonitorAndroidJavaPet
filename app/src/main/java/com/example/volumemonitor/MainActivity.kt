package com.example.volumemonitor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.volumemonitor.core.VolumeMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private lateinit var volumeTextView: TextView
    private lateinit var jsonTextView: TextView
    private lateinit var timestampTextView: TextView
    private lateinit var usbStatusTextView: TextView
    private lateinit var arduinoResponseTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var settingsButton: ImageButton

    private var volumeService: VolumeMonitorService? = null
    private var isBound = false
    private lateinit var audioManager: AudioManager

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val responseHistory = StringBuilder() // Для накопления истории

    // Receiver для обновления громкости
    private val volumeUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("VOLUME_UPDATED" == intent.action) {
                updateVolumeDisplay()
            }
        }
    }

    // Receiver для статуса USB
    private val usbStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("USB_STATUS_UPDATED" == intent.action) {
                updateUsbStatus()
            }
        }
    }

    // Receiver для ответов от Arduino
    private val arduinoResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ARDUINO_RESPONSE") {
                var response = intent.getStringExtra("response") ?: ""

                // Очищаем от лишних символов
                response = response.trim()

                // Убираем обрамление [ и ], если оно есть
                if (response.startsWith("[") && response.endsWith("]")) {
                    response = response.substring(1, response.length - 1)
                }

                // Добавляем в историю с временной меткой
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val formattedResponse = "[$timestamp] $response"

                // Добавляем в начало истории (чтобы новые сообщения были сверху)
                responseHistory.insert(0, "$formattedResponse\n")

                // Ограничиваем историю (например, последние 10 сообщений)
                val lines = responseHistory.toString().split("\n")
                if (lines.size > 10) {
                    responseHistory.clear()
                    responseHistory.append(lines.take(10).joinToString("\n"))
                }

                // Обновляем TextView
                arduinoResponseTextView.text = responseHistory.toString()

                Log.d("ARDUINO_DEBUG", "Получено: $response")
            }
        }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Сервис подключен")
            val binder = service as VolumeMonitorService.LocalBinder
            volumeService = binder.getService()
            isBound = true
            updateVolumeDisplay()
            updateUsbStatus()
            Toast.makeText(this@MainActivity, "Сервис запущен", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Сервис отключен")
            isBound = false
            volumeService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "=== MainActivity onCreate ===")

        initUIElements()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        registerReceivers()
        startAndBindService()
        updateVolumeDisplay()
        updateUsbStatus()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        unregisterReceiver(volumeUpdateReceiver)
        unregisterReceiver(usbStatusReceiver)
        unregisterReceiver(arduinoResponseReceiver)
    }

    private fun initUIElements() {
        volumeTextView = findViewById(R.id.volumeTextView)
        jsonTextView = findViewById(R.id.jsonTextView)
        timestampTextView = findViewById(R.id.timestampTextView)
        usbStatusTextView = findViewById(R.id.usbStatusTextView)
        arduinoResponseTextView = findViewById(R.id.arduinoResponseTextView) // добавить в XML
        refreshButton = findViewById(R.id.refreshButton)
        settingsButton = findViewById(R.id.settingsButton)
    }

    private fun setupButtons() {
        refreshButton.setOnClickListener {
            updateVolumeDisplay()
            // Можно отправить ping для проверки связи
            volumeService?.sendCommand("ping")
            Toast.makeText(this@MainActivity, "Обновлено", Toast.LENGTH_SHORT).show()
        }
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun registerReceivers() {
        registerReceiver(volumeUpdateReceiver, IntentFilter("VOLUME_UPDATED"))
        registerReceiver(usbStatusReceiver, IntentFilter("USB_STATUS_UPDATED"))
        registerReceiver(arduinoResponseReceiver, IntentFilter("ARDUINO_RESPONSE"))
        Log.d(TAG, "Broadcast Receiver'ы зарегистрированы")
    }

    private fun startAndBindService() {
        if (!isBound) {
            val serviceIntent = Intent(this, VolumeMonitorService::class.java)
            startService(serviceIntent)
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
            Log.d(TAG, "Сервис запущен и привязан")
        }
    }

    private fun updateVolumeDisplay() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeTextView.text = "Громкость: $currentVolume / $maxVolume"

        // Преобразование из диапазона 0-30 в 0-255
        val targetVolume = if (currentVolume == 0) {
            0 // Явно устанавливаем 0 для минимального значения
        } else {
            // Для остальных значений используем пропорциональное преобразование
            // Формула: (currentValue * 255) / 30
            // Используем Math.round для правильного округления
            Math.round(currentVolume * 255.0 / 30.0).coerceIn(0, 255)
        }

        jsonTextView.text = "JSON: {\"volume\":$currentVolume,\"set_volume\":$targetVolume}"
        timestampTextView.text = "Время: ${timeFormat.format(Date())}"


        if (isBound) {
            volumeService?.sendVolumeData(targetVolume)
        }
    }

    private fun updateUsbStatus() {
        val status = if (isBound && volumeService != null) {
            volumeService!!.usbStatus
        } else {
            "Сервис не доступен"
        }
        usbStatusTextView.text = "Статус USB: $status"
    }
}