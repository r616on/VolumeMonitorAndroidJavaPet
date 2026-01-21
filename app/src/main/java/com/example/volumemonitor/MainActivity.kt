package com.example.volumemonitor

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.example.volumemonitor.core.VolumeMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val TAG = "MainActivity"

    // UI элементы
    private var volumeTextView: TextView? = null
    private var jsonTextView: TextView? = null
    private var timestampTextView: TextView? = null
    private var usbStatusTextView: TextView? = null
    private var selectedDeviceTextView: TextView? = null
    private var usbDevicesSpinner: Spinner? = null
    private var usbDevicesContainer: LinearLayout? = null
    private var refreshButton: Button? = null
    private var usbPermissionButton: Button? = null
    private var usbScanButton: Button? = null
    private var selectDeviceButton: Button? = null

    // Сервис и менеджеры
    private var volumeService: VolumeMonitorService? = null
    private var isBound = false
    private var audioManager: AudioManager? = null
    private var usbManager: UsbManager? = null

    // Списки устройств
    private val connectedUsbDevices: MutableList<UsbDevice> = ArrayList()
    private var selectedUsbDevice: UsbDevice? = null

    // Формат времени
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Broadcast Receiver для обновления громкости
    private val volumeUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("VOLUME_UPDATED" == intent.action) {
                runOnUiThread { updateVolumeDisplay() }
            }
        }
    }

    // Broadcast Receiver для статуса USB
    private val usbStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("USB_STATUS_UPDATED" == intent.action) {
                runOnUiThread {
                    val status = intent.getStringExtra("status")
                    if (status != null && usbStatusTextView != null) {
                        usbStatusTextView!!.text = "Статус: $status"
                    }
                    scanUsbDevices() // Обновить список устройств
                }
            }
        }
    }

    // Service Connection
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Сервис подключен")
            try {
                val binder = service as VolumeMonitorService.LocalBinder
                volumeService = binder.getService()
                isBound = true
                runOnUiThread {
                    updateVolumeDisplay()
                    updateUsbStatus()
                    scanUsbDevices()
                    Toast.makeText(this@MainActivity, "Сервис запущен", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка подключения к сервису: " + e.message)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Сервис отключен")
            isBound = false
            volumeService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== MainActivity onCreate ===")
        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "Layout установлен успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка установки layout: " + e.message)
            Toast.makeText(this, "Ошибка загрузки интерфейса", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Инициализация UI элементов
        initUIElements()

        // Инициализация системных сервисов
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (audioManager == null) {
            Log.e(TAG, "AudioManager не доступен")
            showError("AudioManager не доступен")
        }
        if (usbManager == null) {
            Log.e(TAG, "UsbManager не доступен")
            showError("USB не поддерживается на этом устройстве")
        }

        // Настройка кнопок
        setupButtons()

        // Настройка Spinner
        setupSpinner()

        // Регистрация Broadcast Receiver'ов
        registerReceivers()

        // Обновление интерфейса
        updateVolumeDisplay()
        updateUsbStatus()
        scanUsbDevices()

        // Запуск сервиса
        startAndBindService()
    }

    private fun initUIElements() {
        volumeTextView = findViewById(R.id.volumeTextView)
        jsonTextView = findViewById(R.id.jsonTextView)
        timestampTextView = findViewById(R.id.timestampTextView)
        usbStatusTextView = findViewById(R.id.usbStatusTextView)
        selectedDeviceTextView = findViewById(R.id.selectedDeviceTextView)
        usbDevicesSpinner = findViewById(R.id.usbDevicesSpinner)
        usbDevicesContainer = findViewById(R.id.usbDevicesContainer)
        refreshButton = findViewById(R.id.refreshButton)
        usbPermissionButton = findViewById(R.id.usbPermissionButton)
        usbScanButton = findViewById(R.id.usbScanButton)
        selectDeviceButton = findViewById(R.id.selectDeviceButton)

        // Проверка элементов
        if (volumeTextView == null) Log.w(TAG, "volumeTextView не найден")
        if (jsonTextView == null) Log.w(TAG, "jsonTextView не найден")
        if (timestampTextView == null) Log.w(TAG, "timestampTextView не найден")
        if (usbStatusTextView == null) Log.w(TAG, "usbStatusTextView не найден")
        if (selectedDeviceTextView == null) Log.w(TAG, "selectedDeviceTextView не найден")
        if (usbDevicesSpinner == null) Log.w(TAG, "usbDevicesSpinner не найден")
        if (usbDevicesContainer == null) Log.w(TAG, "usbDevicesContainer не найден")
        if (refreshButton == null) Log.w(TAG, "refreshButton не найден")
        if (usbPermissionButton == null) Log.w(TAG, "usbPermissionButton не найден")
        if (usbScanButton == null) Log.w(TAG, "usbScanButton не найден")
        if (selectDeviceButton == null) Log.w(TAG, "selectDeviceButton не найден")
    }

    private fun showError(message: String) {
        runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
    }

    private fun setupButtons() {
        refreshButton!!.setOnClickListener {
            updateVolumeDisplay()
            Toast.makeText(this@MainActivity, "Обновлено", Toast.LENGTH_SHORT).show()
        }
        usbPermissionButton!!.setOnClickListener { requestUsbPermissionForAllDevices() }
        usbScanButton!!.setOnClickListener {
            scanUsbDevices()
            Toast.makeText(this@MainActivity, "Сканирование USB...", Toast.LENGTH_SHORT).show()
        }
        selectDeviceButton!!.setOnClickListener { selectCurrentSpinnerDevice() }
    }

    private fun setupSpinner() {
        // Настройка адаптера для Spinner
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ArrayList<String>()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        usbDevicesSpinner!!.adapter = adapter

        // Обработчик выбора устройства
        usbDevicesSpinner!!.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                if (position > 0 && position <= connectedUsbDevices.size) {
                    selectedUsbDevice = connectedUsbDevices[position - 1]
                    updateSelectedDeviceInfo()
                } else {
                    selectedUsbDevice = null
                    if (selectedDeviceTextView != null) {
                        selectedDeviceTextView!!.text = "Устройство не выбрано"
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedUsbDevice = null
                if (selectedDeviceTextView != null) {
                    selectedDeviceTextView!!.text = "Устройство не выбрано"
                }
            }
        }
    }

    private fun selectCurrentSpinnerDevice() {
        val position = usbDevicesSpinner!!.selectedItemPosition
        if (position > 0 && position <= connectedUsbDevices.size) {
            selectedUsbDevice = connectedUsbDevices[position - 1]

            // Сохраняем выбранное устройство в сервисе
            if (isBound && volumeService != null) {
                volumeService!!.setSelectedUsbDevice(selectedUsbDevice)
                Toast.makeText(
                    this,
                    "Устройство выбрано: " +
                            selectedUsbDevice!!.deviceName,
                    Toast.LENGTH_SHORT
                ).show()
            }
            updateSelectedDeviceInfo()
        } else {
            Toast.makeText(this, "Выберите устройство из списка", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSelectedDeviceInfo() {
        if (selectedUsbDevice != null && selectedDeviceTextView != null) {
            var info = "Выбрано: " + selectedUsbDevice!!.deviceName + "\n"
            info += "VID: 0x" + Integer.toHexString(selectedUsbDevice!!.vendorId) + "\n"
            info += "PID: 0x" + Integer.toHexString(selectedUsbDevice!!.productId)
            if (usbManager!!.hasPermission(selectedUsbDevice)) {
                info += "\n✅ Разрешение есть"
            } else {
                info += "\n❌ Нет разрешения"
            }
            selectedDeviceTextView!!.text = info
        }
    }

    private fun registerReceivers() {
        try {
            val volumeFilter = IntentFilter("VOLUME_UPDATED")
            registerReceiver(volumeUpdateReceiver, volumeFilter)
            val usbStatusFilter = IntentFilter("USB_STATUS_UPDATED")
            registerReceiver(usbStatusReceiver, usbStatusFilter)
            Log.d(TAG, "Broadcast Receiver'ы зарегистрированы")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка регистрации Receiver'ов: " + e.message)
        }
    }

    private fun startAndBindService() {
        try {
            val serviceIntent = Intent(this, VolumeMonitorService::class.java)
            startService(serviceIntent)
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
            Log.d(TAG, "Сервис запущен и привязан")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сервиса: " + e.message)
            showError("Ошибка запуска сервиса мониторинга")
        }
    }

    private fun updateVolumeDisplay() {
        runOnUiThread {
            try {
                if (audioManager != null) {
                    val currentVolume = audioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (volumeTextView != null) {
                        volumeTextView!!.text = "Громкость: $currentVolume / $maxVolume"
                    }
                    if (jsonTextView != null) {
                        jsonTextView!!.text = "JSON: {\"volume\":$currentVolume}"
                    }
                    if (timestampTextView != null) {
                        timestampTextView!!.text = "Время: " + timeFormat.format(Date())
                    }

                    // Отправка данных на выбранное устройство
                    if (isBound && volumeService != null) {
                        if (selectedUsbDevice != null) {
                            volumeService!!.sendVolumeDataToDevice(currentVolume, selectedUsbDevice!!)
                        } else {
                            volumeService!!.sendVolumeData(currentVolume)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления громкости: " + e.message)
            }
        }
    }

    private fun updateUsbStatus() {
        runOnUiThread {
            try {
                if (usbStatusTextView != null) {
                    if (isBound && volumeService != null) {
                        val connected = volumeService!!.isUsbConnected
                        val status = volumeService!!.usbStatus
                        var statusText = "Статус: " + if (connected) "ПОДКЛЮЧЕНО" else "НЕТ ПОДКЛЮЧЕНИЯ"
                        if (selectedUsbDevice != null) {
                            statusText += "\nВыбрано: " + selectedUsbDevice!!.deviceName
                        }
                        usbStatusTextView!!.text = statusText
                    } else {
                        usbStatusTextView!!.text = "Статус: Сервис не доступен"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления статуса USB: " + e.message)
            }
        }
    }

    private fun scanUsbDevices() {
        runOnUiThread {
            try {
                if (usbDevicesSpinner == null) return@runOnUiThread
                connectedUsbDevices.clear()
                if (usbManager == null) {
                    updateSpinnerWithMessage("USB менеджер не доступен")
                    return@runOnUiThread
                }
                val deviceList = usbManager!!.deviceList

                // Обновляем Spinner
                val adapter = usbDevicesSpinner!!.adapter as ArrayAdapter<String>
                adapter.clear()
                adapter.add("-- Выберите устройство --")
                if (deviceList == null || deviceList.isEmpty()) {
                    adapter.add("Нет USB устройств")
                    adapter.notifyDataSetChanged()
                    return@runOnUiThread
                }

                // Добавляем устройства в список
                var count = 1
                for (device in deviceList.values) {
                    connectedUsbDevices.add(device)
                    var itemText = count.toString() + ". " + device.deviceName +
                            " (VID: 0x" + Integer.toHexString(device.vendorId) +
                            ", PID: 0x" + Integer.toHexString(device.productId) + ")"
                    if (device.vendorId == 6790) {
                        itemText += " [CH340 Arduino]"
                    }
                    itemText += if (!usbManager!!.hasPermission(device)) {
                        " [❌ Нет разрешения]"
                    } else {
                        " [✅ Разрешено]"
                    }
                    adapter.add(itemText)
                    count++
                }
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    this@MainActivity,
                    "Найдено устройств: " + deviceList.size,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сканирования USB: " + e.message)
                updateSpinnerWithMessage("Ошибка сканирования")
            }
        }
    }

    private fun updateSpinnerWithMessage(message: String) {
        if (usbDevicesSpinner != null) {
            val adapter = usbDevicesSpinner!!.adapter as ArrayAdapter<String>
            adapter.clear()
            adapter.add(message)
            adapter.notifyDataSetChanged()
        }
    }

    private fun requestUsbPermissionForAllDevices() {
        if (usbManager == null) {
            Toast.makeText(this, "USB менеджер не доступен", Toast.LENGTH_SHORT).show()
            return
        }
        val deviceList = usbManager!!.deviceList
        if (deviceList == null || deviceList.isEmpty()) {
            Toast.makeText(this, "Нет USB устройств", Toast.LENGTH_SHORT).show()
            return
        }
        var requested = 0
        for (device in deviceList.values) {
            if (!usbManager!!.hasPermission(device)) {
                try {
                    val permissionIntent = android.app.PendingIntent.getBroadcast(
                        this,
                        device.deviceId,
                        Intent("com.example.volumemonitor.USB_PERMISSION"),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager!!.requestPermission(device, permissionIntent)
                    requested++
                    Log.d(TAG, "Запрос разрешения для: " + device.deviceName)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка запроса разрешения: " + e.message)
                }
            }
        }
        if (requested > 0) {
            Toast.makeText(
                this,
                "Запросы отправлены для $requested устройств",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Все устройства уже имеют разрешения",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        updateVolumeDisplay()
        updateUsbStatus()
        scanUsbDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")

        // Отвязка от сервиса
        if (isBound) {
            try {
                unbindService(serviceConnection)
                isBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отвязки от сервиса: " + e.message)
            }
        }

        // Отмена регистрации Receiver'ов
        try {
            unregisterReceiver(volumeUpdateReceiver)
            unregisterReceiver(usbStatusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отмены регистрации Receiver'ов: " + e.message)
        }
    }
}
