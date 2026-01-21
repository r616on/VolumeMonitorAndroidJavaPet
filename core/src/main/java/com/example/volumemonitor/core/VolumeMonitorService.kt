package com.example.volumemonitor.core

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.util.Log

class VolumeMonitorService : Service() {
    private val TAG = "VolumeMonitor"
    private val ACTION_USB_PERMISSION = "com.example.volumemonitor.USB_PERMISSION"

    private var audioManager: AudioManager? = null
    private var usbManager: UsbManager? = null
    private var selectedUsbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbEndpoint: UsbEndpoint? = null
    private var previousVolume = -1
    private val binder: IBinder = LocalBinder()

    var isUsbConnected = false
    var usbStatus = "Инициализация..."
        private set

    // USB Receiver
    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "USB Broadcast: $action")
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.i(TAG, "USB разрешение получено для: " + device.deviceName)
                            usbStatus = "Разрешение получено: " + device.deviceName

                            // Если это выбранное устройство - подключаемся
                            if (selectedUsbDevice != null &&
                                selectedUsbDevice!!.deviceId == device.deviceId
                            ) {
                                setupUsbConnection(device)
                            }
                        }
                    } else {
                        Log.e(TAG, "USB разрешение отклонено")
                        usbStatus = "Разрешение отклонено"
                        sendUsbStatusUpdate("Разрешение отклонено")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null) {
                    Log.i(TAG, "USB устройство подключено: " + device.deviceName)
                    usbStatus = "Устройство подключено: " + device.deviceName
                    requestUsbPermission(device)
                    sendUsbStatusUpdate("Устройство подключено: " + device.deviceName)
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                Log.i(TAG, "USB устройство отключено")
                usbStatus = "Устройство отключено"
                isUsbConnected = false
                closeUsbConnection()
                sendUsbStatusUpdate("Устройство отключено")
            }
        }
    }

    // Volume Receiver
    private val volumeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("android.media.VOLUME_CHANGED_ACTION" == intent.action) {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val currentVolume = audioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (previousVolume != currentVolume) {
                        sendVolumeData(currentVolume)
                        Log.d(TAG, "Громкость изменилась: $currentVolume")
                        val volumeUpdateIntent = Intent("VOLUME_UPDATED")
                        volumeUpdateIntent.putExtra("volume", currentVolume)
                        sendBroadcast(volumeUpdateIntent)
                        previousVolume = currentVolume
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VolumeMonitorService {
            return this@VolumeMonitorService
        }
    }

    // Метод для установки выбранного устройства
    fun setSelectedUsbDevice(device: UsbDevice?) {
        selectedUsbDevice = device
        Log.d(
            TAG,
            "Выбрано устройство: " +
                    if (device != null) device.deviceName else "null"
        )

        // Закрываем старое соединение
        closeUsbConnection()
        if (device != null) {
            // Проверяем разрешение
            if (usbManager!!.hasPermission(device)) {
                setupUsbConnection(device)
            } else {
                usbStatus = "Нет разрешения для: " + device.deviceName
                requestUsbPermission(device)
            }
        }
    }

    // Метод для отправки на конкретное устройство
    fun sendVolumeDataToDevice(volumeLevel: Int, device: UsbDevice) {
        Log.d(TAG, "Отправка на устройство: " + device.deviceName)

        // Если это другое устройство - переключаемся
        if (selectedUsbDevice == null ||
            selectedUsbDevice!!.deviceId != device.deviceId
        ) {
            setSelectedUsbDevice(device)
        }

        // Отправляем данные
        sendVolumeData(volumeLevel)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== Создание сервиса ===")
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        // Регистрация USB Receiver'ов
        val usbFilter = IntentFilter()
        usbFilter.addAction(ACTION_USB_PERMISSION)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbReceiver, usbFilter)

        // Регистрация Volume Receiver'а
        val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        registerReceiver(volumeReceiver, volumeFilter)

        // Инициализация громкости
        if (audioManager != null) {
            previousVolume = audioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        Log.d(TAG, "Сервис создан успешно")
    }

    private fun requestUsbPermission(device: UsbDevice?) {
        if (device == null) return
        try {
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager!!.requestPermission(device, permissionIntent)
            Log.d(TAG, "Запрос разрешения отправлен для: " + device.deviceName)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса разрешения: " + e.message)
        }
    }

    private fun setupUsbConnection(device: UsbDevice?) {
        if (device == null) {
            Log.e(TAG, "Устройство null")
            return
        }
        Log.i(TAG, "Настройка USB соединения для: " + device.deviceName)

        // Закрываем старое соединение
        closeUsbConnection()

        // Открываем устройство
        usbConnection = usbManager!!.openDevice(device)
        if (usbConnection == null) {
            Log.e(TAG, "Не удалось открыть USB устройство")
            usbStatus = "Ошибка открытия USB: " + device.deviceName
            return
        }

        // Ищем подходящий интерфейс
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbConnection!!.claimInterface(usbInterface, true)) {
                Log.d(TAG, "Интерфейс захвачен: $i")

                // Ищем OUT endpoint
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        usbEndpoint = endpoint
                        selectedUsbDevice = device
                        isUsbConnected = true
                        usbStatus = "Подключено: " + device.deviceName
                        Log.i(TAG, "✅ USB соединение установлено!")
                        Log.i(TAG, "Используется endpoint: 0x" + Integer.toHexString(endpoint.address))

                        // Отправляем тестовое сообщение
                        sendTestMessage()
                        sendUsbStatusUpdate("USB подключено: " + device.deviceName)
                        return
                    }
                }
                usbConnection!!.releaseInterface(usbInterface)
            }
        }
        Log.e(TAG, "Не найден подходящий OUT endpoint")
        usbStatus = "Не найден OUT endpoint для: " + device.deviceName
        closeUsbConnection()
    }

    private fun sendTestMessage() {
        if (!isUsbConnected || usbConnection == null || usbEndpoint == null) {
            return
        }
        val testMessage =
            "{\"test\":\"Android connected to " + selectedUsbDevice!!.deviceName + "\"}\n"
        try {
            val data = testMessage.toByteArray(charset("UTF-8"))
            val result = usbConnection!!.bulkTransfer(usbEndpoint, data, data.size, 1000)
            if (result >= 0) {
                Log.i(TAG, "✅ Тестовое сообщение отправлено успешно")
            } else {
                Log.e(TAG, "❌ Ошибка отправки тестового сообщения: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки тестового сообщения: " + e.message)
        }
    }

    private fun closeUsbConnection() {
        if (usbConnection != null) {
            usbConnection!!.close()
            usbConnection = null
        }
        selectedUsbDevice = null
        usbEndpoint = null
        isUsbConnected = false
    }

    fun sendVolumeData(volumeLevel: Int) {
        var jsonData = "{\"volume\":$volumeLevel}"
        Log.d(TAG, "Подготовка данных: $jsonData")

        // 1. Эмуляция в Logcat
        Log.i(TAG, "Для Arduino: $jsonData")

        // 2. Отправка через USB (если подключено)
        if (isUsbConnected && usbConnection != null && usbEndpoint != null && selectedUsbDevice != null) {
            try {
                // ВАЖНО: добавляем символ новой строки!
                jsonData = "$jsonData\n"
                val data = jsonData.toByteArray(charset("UTF-8"))
                Log.d(
                    TAG,
                    "Отправка на " + selectedUsbDevice!!.deviceName + ": " + data.size + " байт"
                )

                // Отправляем данные
                val result = usbConnection!!.bulkTransfer(usbEndpoint, data, data.size, 2000)
                if (result >= 0) {
                    Log.i(
                        TAG,
                        "✅ USB данные отправлены успешно на " +
                                selectedUsbDevice!!.deviceName + ": " + result + " байт"
                    )
                } else {
                    Log.e(
                        TAG,
                        "❌ Ошибка отправки USB на " +
                                selectedUsbDevice!!.deviceName + ", код: " + result
                    )
                    if (result == -1) {
                        Log.e(TAG, "Таймаут USB передачи")
                    } else if (result == -2) {
                        Log.e(TAG, "Ошибка USB передачи")
                    }

                    // Попытка переподключения
                    setupUsbConnection(selectedUsbDevice)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Исключение при отправке USB: " + e.message)
                e.printStackTrace()
            }
        } else {
            val deviceName = selectedUsbDevice?.deviceName ?: "не выбрано"
            Log.w(TAG, "⚠️ USB не подключено (устройство: $deviceName). Только эмуляция.")
        }
    }

    private fun sendUsbStatusUpdate(status: String) {
        val statusIntent = Intent("USB_STATUS_UPDATED")
        statusIntent.putExtra("status", status)
        sendBroadcast(statusIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== Уничтожение сервиса ===")

        // Отмена регистрации Receiver'ов
        try {
            unregisterReceiver(usbReceiver)
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отмены регистрации Receiver'ов: " + e.message)
        }

        // Закрытие USB соединения
        closeUsbConnection()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
