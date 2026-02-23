package com.example.volumemonitor

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
import java.io.OutputStream
import java.io.InputStream

class VolumeMonitorService : Service() {
    private val TAG = "VolumeMonitor"
    private val ACTION_USB_PERMISSION = "com.example.volumemonitor.USB_PERMISSION"

    // VENDOR_ID и PRODUCT_ID для Arduino Nano
    private val ARDUINO_VENDOR_ID = 0x2341  // Arduino
    private val ARDUINO_PRODUCT_ID = 0x0043  // Arduino Nano

    // Классы интерфейсов для CDC/ACM
    private val CDC_ACM_INTERFACE_CLASS = 0x02
    private val CDC_DATA_INTERFACE_CLASS = 0x0A

    private var audioManager: AudioManager? = null
    private var usbManager: UsbManager? = null
    private var selectedUsbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
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
                            Log.i(TAG, "USB разрешение получено для: ${device.deviceName}")
                            usbStatus = "Разрешение получено: ${device.deviceName}"

                            // Проверяем, это Arduino?
                            if (isArduinoDevice(device)) {
                                setupUsbSerialConnection(device)
                            } else {
                                usbStatus = "Не Arduino устройство"
                                Log.w(TAG, "Это не Arduino устройство")
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
                    Log.i(TAG, "USB устройство подключено: ${device.deviceName}")
                    usbStatus = "Устройство подключено: ${device.deviceName}"

                    if (isArduinoDevice(device)) {
                        requestUsbPermission(device)
                    } else {
                        usbStatus = "Не Arduino устройство"
                        Log.w(TAG, "Подключено не Arduino устройство: ${device.vendorId}:${device.productId}")
                    }
                    sendUsbStatusUpdate("Устройство подключено: ${device.deviceName}")
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
                    val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
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
        fun getService(): VolumeMonitorService = this@VolumeMonitorService
    }

    // Проверка, что это Arduino устройство
    private fun isArduinoDevice(device: UsbDevice): Boolean {
        return (device.vendorId == ARDUINO_VENDOR_ID &&
                device.productId == ARDUINO_PRODUCT_ID)
    }

    // Метод для установки выбранного устройства
    fun setSelectedUsbDevice(device: UsbDevice?) {
        selectedUsbDevice = device
        Log.d(TAG, "Выбрано устройство: ${device?.deviceName ?: "null"}")

        closeUsbConnection()
        if (device != null) {
            if (usbManager?.hasPermission(device) == true) {
                setupUsbSerialConnection(device)
            } else {
                usbStatus = "Нет разрешения для: ${device.deviceName}"
                requestUsbPermission(device)
            }
        }
    }

    // Запрос разрешения USB
    private fun requestUsbPermission(device: UsbDevice?) {
        if (device == null) return
        try {
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager?.requestPermission(device, permissionIntent)
            Log.d(TAG, "Запрос разрешения отправлен для: ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса разрешения: ${e.message}")
        }
    }

    // Настройка соединения через USB Serial
    private fun setupUsbSerialConnection(device: UsbDevice?) {
        if (device == null) {
            Log.e(TAG, "Устройство null")
            return
        }

        Log.i(TAG, "Настройка USB Serial соединения для: ${device.deviceName}")
        Log.i(TAG, "Интерфейсы: ${device.interfaceCount}")

        // Закрываем старое соединение
        closeUsbConnection()

        // Открываем устройство
        usbConnection = usbManager?.openDevice(device)
        if (usbConnection == null) {
            Log.e(TAG, "Не удалось открыть USB устройство")
            usbStatus = "Ошибка открытия USB: ${device.deviceName}"
            return
        }

        // Ищем интерфейс CDC Data (класс 0x0A)
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            Log.d(TAG, "Интерфейс $i: class=0x${usbInterface.interfaceClass.toString(16)}, " +
                    "subclass=0x${usbInterface.interfaceSubclass.toString(16)}")

            if (usbInterface.interfaceClass == CDC_DATA_INTERFACE_CLASS) {
                Log.d(TAG, "Найден CDC Data интерфейс")

                if (usbConnection!!.claimInterface(usbInterface, true)) {
                    Log.d(TAG, "Интерфейс захвачен: $i")

                    // Ищем IN и OUT endpoints
                    var inEndpoint: UsbEndpoint? = null
                    var outEndpoint: UsbEndpoint? = null

                    for (j in 0 until usbInterface.endpointCount) {
                        val endpoint = usbInterface.getEndpoint(j)
                        Log.d(TAG, "Endpoint $j: type=${endpoint.type}, address=0x${endpoint.address.toString(16)}, " +
                                "direction=${endpoint.direction}")

                        when (endpoint.direction) {
                            UsbConstants.USB_DIR_IN -> inEndpoint = endpoint
                            UsbConstants.USB_DIR_OUT -> outEndpoint = endpoint
                        }
                    }

                    if (outEndpoint != null) {
                        selectedUsbDevice = device
                        isUsbConnected = true
                        usbStatus = "Подключено: ${device.deviceName}"

                        // Создаем OutputStream для отправки данных
                        outputStream = object : OutputStream() {
                            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                                usbConnection?.bulkTransfer(
                                    outEndpoint!!,
                                    buffer,
                                    offset,
                                    count,
                                    1000
                                )
                            }

                            override fun write(oneByte: Int) {
                                val buffer = byteArrayOf(oneByte.toByte())
                                usbConnection?.bulkTransfer(
                                    outEndpoint!!,
                                    buffer,
                                    1,
                                    1000
                                )
                            }
                        }

                        Log.i(TAG, "✅ USB Serial соединение установлено!")
                        sendUsbStatusUpdate("USB подключено: ${device.deviceName}")

                        // Отправляем тестовое сообщение
                        sendTestMessage()
                        return
                    } else {
                        Log.e(TAG, "Не найден OUT endpoint")
                        usbConnection!!.releaseInterface(usbInterface)
                    }
                }
            }
        }

        Log.e(TAG, "Не найден подходящий CDC Data интерфейс")
        usbStatus = "Не найден CDC интерфейс для: ${device.deviceName}"
        closeUsbConnection()
    }

    private fun sendTestMessage() {
        if (!isUsbConnected || outputStream == null) {
            return
        }
        val testMessage = "{\"test\":\"Android connected\"}\n"
        try {
            outputStream!!.write(testMessage.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "✅ Тестовое сообщение отправлено успешно")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки тестового сообщения: ${e.message}")
        }
    }

    private fun closeUsbConnection() {
        outputStream?.close()
        inputStream?.close()
        outputStream = null
        inputStream = null

        usbConnection?.close()
        usbConnection = null
        selectedUsbDevice = null
        isUsbConnected = false
    }

    // ОСНОВНОЙ МЕТОД ОТПРАВКИ ДАННЫХ - ИСПРАВЛЕН
    fun sendVolumeData(volumeLevel: Int) {
        // Формируем JSON
        val jsonData = "{\"volume\":$volumeLevel}\n"
        Log.d(TAG, "Отправка данных: $jsonData")

        // Отправка через USB Serial
        if (isUsbConnected && outputStream != null) {
            try {
                // Преобразуем в байты
                val dataBytes = jsonData.toByteArray(Charsets.UTF_8)

                // Логируем HEX представление для отладки
                val hexString = dataBytes.joinToString(" ") {
                    String.format("%02X", it)
                }
                Log.d(TAG, "Отправляемые байты (HEX): $hexString")
                Log.d(TAG, "Отправляемые байты (ASCII): ${String(dataBytes, Charsets.US_ASCII)}")

                // Отправляем данные
                outputStream!!.write(dataBytes)
                outputStream!!.flush() // Важно: сбрасываем буфер

                Log.i(TAG, "✅ Данные отправлены успешно: $volumeLevel")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка отправки данных: ${e.message}")
                e.printStackTrace()

                // Попытка переподключения
                selectedUsbDevice?.let { setupUsbSerialConnection(it) }
            }
        } else {
            val deviceName = selectedUsbDevice?.deviceName ?: "не выбрано"
            Log.w(TAG, "⚠️ USB не подключено (устройство: $deviceName)")
        }
    }

    private fun sendUsbStatusUpdate(status: String) {
        val statusIntent = Intent("USB_STATUS_UPDATED")
        statusIntent.putExtra("status", status)
        sendBroadcast(statusIntent)
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
        previousVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        Log.d(TAG, "Сервис создан успешно")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== Уничтожение сервиса ===")

        try {
            unregisterReceiver(usbReceiver)
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отмены регистрации Receiver'ов: ${e.message}")
        }

        closeUsbConnection()
    }

    override fun onBind(intent: Intent): IBinder = binder
}