package com.example.volumemonitor;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import java.util.HashMap;

public class VolumeMonitorService extends Service {
    private static final String TAG = "VolumeMonitor";
    private static final String ACTION_USB_PERMISSION = "com.example.volumemonitor.USB_PERMISSION";

    private AudioManager audioManager;
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection usbConnection;
    private UsbEndpoint usbEndpoint;
    private int previousVolume = -1;
    private final IBinder binder = new LocalBinder();

    private boolean isUsbConnected = false;
    private String usbStatus = "Инициализация...";

    // USB Receiver
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "USB Broadcast: " + action);

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.i(TAG, "USB разрешение получено для: " + device.getDeviceName());
                            usbStatus = "Разрешение получено";
                            setupUsbConnection(device);
                        }
                    } else {
                        Log.e(TAG, "USB разрешение отклонено");
                        usbStatus = "Разрешение отклонено";
                        sendUsbStatusUpdate("Разрешение отклонено");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    Log.i(TAG, "USB устройство подключено: " + device.getDeviceName());
                    usbStatus = "Устройство подключено";
                    requestUsbPermission(device);
                    sendUsbStatusUpdate("Устройство подключено: " + device.getDeviceName());
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "USB устройство отключено");
                usbStatus = "Устройство отключено";
                isUsbConnected = false;
                closeUsbConnection();
                sendUsbStatusUpdate("Устройство отключено");
            }
        }
    };

    // Volume Receiver
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (previousVolume != currentVolume) {
                        sendVolumeData(currentVolume);
                        Log.d(TAG, "Громкость изменилась: " + currentVolume);

                        Intent volumeUpdateIntent = new Intent("VOLUME_UPDATED");
                        volumeUpdateIntent.putExtra("volume", currentVolume);
                        sendBroadcast(volumeUpdateIntent);

                        previousVolume = currentVolume;
                    }
                }
            }
        }
    };

    public class LocalBinder extends Binder {
        VolumeMonitorService getService() {
            return VolumeMonitorService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "=== Создание сервиса ===");

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Регистрация USB Receiver'ов
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(ACTION_USB_PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, usbFilter);

        // Регистрация Volume Receiver'а
        IntentFilter volumeFilter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeReceiver, volumeFilter);

        // Поиск существующих USB устройств
        findExistingUsbDevices();

        // Инициализация громкости
        if (audioManager != null) {
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        Log.d(TAG, "Сервис создан успешно");
    }

    private void findExistingUsbDevices() {
        if (usbManager == null) {
            usbStatus = "USB менеджер не доступен";
            return;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) {
            usbStatus = "USB устройств не найдено";
            Log.d(TAG, "USB устройств не найдено");
            return;
        }

        Log.i(TAG, "Найдено USB устройств: " + deviceList.size());

        for (UsbDevice device : deviceList.values()) {
            String deviceInfo = "Устройство: " + device.getDeviceName() +
                    " VID: 0x" + Integer.toHexString(device.getVendorId()) +
                    " PID: 0x" + Integer.toHexString(device.getProductId());
            Log.d(TAG, deviceInfo);

            // Проверяем CH340 (китайские Arduino)
            if (device.getVendorId() == 6790) {
                Log.i(TAG, "Найден CH340 (Arduino клон)");
                usbStatus = "CH340 обнаружен";
            }

            // Проверяем разрешения
            if (usbManager.hasPermission(device)) {
                Log.i(TAG, "Разрешение уже есть");
                setupUsbConnection(device);
            } else {
                Log.i(TAG, "Запрашиваем разрешение");
                requestUsbPermission(device);
            }
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        if (device == null) return;

        try {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            usbManager.requestPermission(device, permissionIntent);
            Log.d(TAG, "Запрос разрешения отправлен для: " + device.getDeviceName());
        } catch (Exception e) {
            Log.e(TAG, "Ошибка запроса разрешения: " + e.getMessage());
        }
    }

    private void setupUsbConnection(UsbDevice device) {
        if (device == null) {
            Log.e(TAG, "Устройство null");
            return;
        }

        Log.i(TAG, "Настройка USB соединения для: " + device.getDeviceName());

        // Закрываем старое соединение
        closeUsbConnection();

        // Открываем устройство
        usbConnection = usbManager.openDevice(device);
        if (usbConnection == null) {
            Log.e(TAG, "Не удалось открыть USB устройство");
            usbStatus = "Ошибка открытия USB";
            return;
        }

        // Ищем подходящий интерфейс
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);

            if (usbConnection.claimInterface(usbInterface, true)) {
                Log.d(TAG, "Интерфейс захвачен: " + i);

                // Ищем OUT endpoint
                for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                    UsbEndpoint endpoint = usbInterface.getEndpoint(j);

                    if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                        usbEndpoint = endpoint;
                        usbDevice = device;
                        isUsbConnected = true;

                        usbStatus = "Подключено: " + device.getDeviceName();
                        Log.i(TAG, "USB соединение установлено!");
                        Log.i(TAG, "Используется endpoint: 0x" + Integer.toHexString(endpoint.getAddress()));

                        // Отправляем тестовое сообщение
                        sendTestMessage();
                        sendUsbStatusUpdate("USB подключено: " + device.getDeviceName());
                        return;
                    }
                }

                usbConnection.releaseInterface(usbInterface);
            }
        }

        Log.e(TAG, "Не найден подходящий OUT endpoint");
        usbStatus = "Не найден OUT endpoint";
        closeUsbConnection();
    }

    private void sendTestMessage() {
        if (!isUsbConnected || usbConnection == null || usbEndpoint == null) {
            return;
        }

        String testMessage = "{\"test\":\"Android connected\"}\n";
        try {
            byte[] data = testMessage.getBytes("UTF-8");
            int result = usbConnection.bulkTransfer(usbEndpoint, data, data.length, 1000);

            if (result >= 0) {
                Log.i(TAG, "Тестовое сообщение отправлено успешно");
            } else {
                Log.e(TAG, "Ошибка отправки тестового сообщения: " + result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка отправки тестового сообщения: " + e.getMessage());
        }
    }

    private void closeUsbConnection() {
        if (usbConnection != null) {
            usbConnection.close();
            usbConnection = null;
        }
        usbDevice = null;
        usbEndpoint = null;
        isUsbConnected = false;
    }

    public void sendVolumeData(int volumeLevel) {
        String jsonData = "{\"volume\":" + volumeLevel + "}\n";
        Log.d(TAG, "Отправка данных: " + jsonData.trim());

        // Эмуляция в Logcat (пока)
        Log.i(TAG, "Для Arduino: " + jsonData.trim());

        // Реальная отправка через USB
        if (isUsbConnected && usbConnection != null && usbEndpoint != null) {
            try {
                byte[] data = jsonData.getBytes("UTF-8");
                int result = usbConnection.bulkTransfer(usbEndpoint, data, data.length, 1000);

                if (result >= 0) {
                    Log.i(TAG, "Данные отправлены успешно: " + result + " байт");
                } else {
                    Log.e(TAG, "Ошибка отправки USB: " + result);
                    // Попытка переподключения
                    if (usbDevice != null) {
                        setupUsbConnection(usbDevice);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Исключение при отправке USB: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "USB не подключен. Данные не отправлены.");
        }
    }

    public String getUsbStatus() {
        return usbStatus;
    }

    public boolean isUsbConnected() {
        return isUsbConnected;
    }

    private void sendUsbStatusUpdate(String status) {
        Intent statusIntent = new Intent("USB_STATUS_UPDATED");
        statusIntent.putExtra("status", status);
        sendBroadcast(statusIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "=== Уничтожение сервиса ===");

        // Отмена регистрации Receiver'ов
        try {
            unregisterReceiver(usbReceiver);
            unregisterReceiver(volumeReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка отмены регистрации Receiver'ов: " + e.getMessage());
        }

        // Закрытие USB соединения
        closeUsbConnection();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}