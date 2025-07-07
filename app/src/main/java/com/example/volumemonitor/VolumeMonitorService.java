package com.example.volumemonitor;

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
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.util.HashMap;

public class VolumeMonitorService extends Service {
    private static final String TAG = "VolumeMonitor";
    private AudioManager audioManager;
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection usbConnection;
    private UsbEndpoint usbEndpoint;
    private int previousVolume = -1;

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (previousVolume != currentVolume) {
                        sendVolumeData(currentVolume);
                        Log.d(TAG, "Volume changed: " + currentVolume);
                        previousVolume = currentVolume;
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Регистрируем приемник изменений громкости
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeReceiver, filter);

        setupUsbConnection();
        // Инициализируем предыдущее значение
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        // Для старых версий Android добавляем уведомление
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            addLegacyNotification();
        }
    }

    private void setupUsbConnection() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null) {
            Log.e(TAG, "No USB devices found");
            return;
        }

        for (UsbDevice device : deviceList.values()) {
            // Arduino Vendor ID обычно 9025 (0x2341)
            if (device.getVendorId() == 9025) {
                usbDevice = device;

                // Поиск подходящего интерфейса и endpoint
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    UsbInterface usbInterface = device.getInterface(i);
                    for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                        UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                        if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                            usbEndpoint = endpoint;
                            usbConnection = usbManager.openDevice(device);
                            if (usbConnection != null) {
                                usbConnection.claimInterface(usbInterface, true);
                                Log.i(TAG, "USB connection established");
                            }
                            break;
                        }
                    }
                    if (usbEndpoint != null) break;
                }
                break;
            }
        }
    }

    private void sendVolumeData(int volumeLevel) {
        // Формируем JSON с текущим значением громкости
        String jsonData = "{\"volume\":" + volumeLevel + "}\n";

        // Реальная отправка на Arduino
        if (usbConnection != null && usbEndpoint != null) {
            byte[] data = jsonData.getBytes();
            int result = usbConnection.bulkTransfer(usbEndpoint, data, data.length, 1000);
            if (result >= 0) {
                Log.d(TAG, "Data sent successfully");
            } else {
                Log.e(TAG, "USB send error: " + result);
            }
        }

        // Эмуляция через Logcat
        Log.i(TAG, "JSON Data: " + jsonData.trim());
    }

    // Добавляем простое уведомление для старых версий Android
    private void addLegacyNotification() {
        android.app.Notification notification = new android.app.Notification.Builder(this)
                .setContentTitle("Volume Monitor")
                .setContentText("Monitoring volume changes")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(volumeReceiver);
        if (usbConnection != null) {
            usbConnection.close();
            usbConnection = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}