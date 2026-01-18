package com.example.volumemonitor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    // UI элементы
    private TextView volumeTextView;
    private TextView jsonTextView;
    private TextView timestampTextView;
    private TextView usbStatusTextView;
    private LinearLayout usbDevicesContainer;
    private Button refreshButton;
    private Button usbPermissionButton;
    private Button usbScanButton;

    // Сервис и менеджеры
    private VolumeMonitorService volumeService;
    private boolean isBound = false;
    private AudioManager audioManager;
    private UsbManager usbManager;

    // Формат времени
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // Broadcast Receiver для обновления громкости
    private final BroadcastReceiver volumeUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("VOLUME_UPDATED".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateVolumeDisplay();
                    }
                });
            }
        }
    };

    // Broadcast Receiver для статуса USB
    private final BroadcastReceiver usbStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("USB_STATUS_UPDATED".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String status = intent.getStringExtra("status");
                        if (status != null && usbStatusTextView != null) {
                            usbStatusTextView.setText("USB: " + status);
                        }
                        scanUsbDevices(); // Обновить список устройств
                    }
                });
            }
        }
    };

    // Service Connection
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Сервис подключен");
            try {
                VolumeMonitorService.LocalBinder binder = (VolumeMonitorService.LocalBinder) service;
                volumeService = binder.getService();
                isBound = true;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateVolumeDisplay();
                        updateUsbStatus();
                        scanUsbDevices();
                        Toast.makeText(MainActivity.this, "Сервис запущен", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Ошибка подключения к сервису: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Сервис отключен");
            isBound = false;
            volumeService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== MainActivity onCreate ===");

        try {
            setContentView(R.layout.activity_main);
            Log.d(TAG, "Layout установлен успешно");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка установки layout: " + e.getMessage());
            Toast.makeText(this, "Ошибка загрузки интерфейса", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Инициализация UI элементов
        initUIElements();

        // Инициализация системных сервисов
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        if (audioManager == null) {
            Log.e(TAG, "AudioManager не доступен");
            showError("AudioManager не доступен");
        }

        if (usbManager == null) {
            Log.e(TAG, "UsbManager не доступен");
            showError("USB не поддерживается на этом устройстве");
        }

        // Настройка кнопок
        setupButtons();

        // Регистрация Broadcast Receiver'ов
        registerReceivers();

        // Обновление интерфейса
        updateVolumeDisplay();
        updateUsbStatus();
        scanUsbDevices();

        // Запуск сервиса
        startAndBindService();
    }

    private void initUIElements() {
        volumeTextView = findViewById(R.id.volumeTextView);
        jsonTextView = findViewById(R.id.jsonTextView);
        timestampTextView = findViewById(R.id.timestampTextView);
        usbStatusTextView = findViewById(R.id.usbStatusTextView);
        usbDevicesContainer = findViewById(R.id.usbDevicesContainer);
        refreshButton = findViewById(R.id.refreshButton);
        usbPermissionButton = findViewById(R.id.usbPermissionButton);
        usbScanButton = findViewById(R.id.usbScanButton);

        // Проверка элементов
        if (volumeTextView == null) Log.w(TAG, "volumeTextView не найден");
        if (jsonTextView == null) Log.w(TAG, "jsonTextView не найден");
        if (timestampTextView == null) Log.w(TAG, "timestampTextView не найден");
        if (usbStatusTextView == null) Log.w(TAG, "usbStatusTextView не найден");
        if (usbDevicesContainer == null) Log.w(TAG, "usbDevicesContainer не найден");
        if (refreshButton == null) Log.w(TAG, "refreshButton не найден");
        if (usbPermissionButton == null) Log.w(TAG, "usbPermissionButton не найден");
        if (usbScanButton == null) Log.w(TAG, "usbScanButton не найден");
    }

    private void showError(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setupButtons() {
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateVolumeDisplay();
                Toast.makeText(MainActivity.this, "Обновлено", Toast.LENGTH_SHORT).show();
            }
        });

        usbPermissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestUsbPermissionForAllDevices();
            }
        });

        usbScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanUsbDevices();
                Toast.makeText(MainActivity.this, "Сканирование USB...", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void registerReceivers() {
        try {
            IntentFilter volumeFilter = new IntentFilter("VOLUME_UPDATED");
            registerReceiver(volumeUpdateReceiver, volumeFilter);

            IntentFilter usbStatusFilter = new IntentFilter("USB_STATUS_UPDATED");
            registerReceiver(usbStatusReceiver, usbStatusFilter);

            Log.d(TAG, "Broadcast Receiver'ы зарегистрированы");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка регистрации Receiver'ов: " + e.getMessage());
        }
    }

    private void startAndBindService() {
        try {
            Intent serviceIntent = new Intent(this, VolumeMonitorService.class);
            startService(serviceIntent);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "Сервис запущен и привязан");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска сервиса: " + e.getMessage());
            showError("Ошибка запуска сервиса мониторинга");
        }
    }

    private void updateVolumeDisplay() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (audioManager != null) {
                        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

                        if (volumeTextView != null) {
                            volumeTextView.setText("Громкость: " + currentVolume + " / " + maxVolume);
                        }

                        if (jsonTextView != null) {
                            jsonTextView.setText("JSON: {\"volume\":" + currentVolume + "}");
                        }

                        if (timestampTextView != null) {
                            timestampTextView.setText("Время: " + timeFormat.format(new Date()));
                        }

                        // Отправка данных на Arduino
                        if (isBound && volumeService != null) {
                            volumeService.sendVolumeData(currentVolume);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обновления громкости: " + e.getMessage());
                }
            }
        });
    }

    private void updateUsbStatus() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (usbStatusTextView != null) {
                        if (isBound && volumeService != null) {
                            boolean connected = volumeService.isUsbConnected();
                            String status = volumeService.getUsbStatus();
                            usbStatusTextView.setText("USB: " + (connected ? "ПОДКЛЮЧЕНО" : "НЕТ ПОДКЛЮЧЕНИЯ"));
                        } else {
                            usbStatusTextView.setText("USB: Сервис не доступен");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обновления статуса USB: " + e.getMessage());
                }
            }
        });
    }

    private void scanUsbDevices() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (usbDevicesContainer == null) return;

                    usbDevicesContainer.removeAllViews();

                    if (usbManager == null) {
                        addTextToContainer("USB менеджер не доступен");
                        return;
                    }

                    HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

                    if (deviceList == null || deviceList.isEmpty()) {
                        addTextToContainer("Нет подключенных USB устройств");
                        return;
                    }

                    // Заголовок
                    addTextToContainer("Найдено устройств: " + deviceList.size());

                    int count = 1;
                    for (UsbDevice device : deviceList.values()) {
                        // Создаем TextView для каждого устройства
                        TextView deviceView = new TextView(MainActivity.this);
                        deviceView.setPadding(16, 8, 16, 8);
                        deviceView.setTextSize(12);

                        String info = "Устройство " + count + ":\n";
                        info += "Имя: " + device.getDeviceName() + "\n";
                        info += "VID: 0x" + Integer.toHexString(device.getVendorId()) + "\n";
                        info += "PID: 0x" + Integer.toHexString(device.getProductId()) + "\n";

                        // Определяем тип
                        if (device.getVendorId() == 6790) {
                            info += "⭐ CH340 (Arduino клон)\n";
                        } else if (device.getVendorId() == 9025) {
                            info += "Arduino оригинальный\n";
                        } else if (device.getVendorId() == 1027) {
                            info += "FTDI преобразователь\n";
                        }

                        info += "Разрешение: " + (usbManager.hasPermission(device) ? "ДА" : "НЕТ");

                        deviceView.setText(info);
                        deviceView.setBackgroundColor(0xFFF0F0F0);

                        usbDevicesContainer.addView(deviceView);

                        // Добавляем разделитель
                        if (count < deviceList.size()) {
                            View separator = new View(MainActivity.this);
                            separator.setLayoutParams(new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
                            separator.setBackgroundColor(0xFFCCCCCC);
                            usbDevicesContainer.addView(separator);
                        }

                        count++;
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Ошибка сканирования USB: " + e.getMessage());
                    addTextToContainer("Ошибка сканирования USB");
                }
            }
        });
    }

    private void addTextToContainer(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setPadding(16, 16, 16, 16);
        textView.setTextSize(14);
        if (usbDevicesContainer != null) {
            usbDevicesContainer.addView(textView);
        }
    }

    private void requestUsbPermissionForAllDevices() {
        if (usbManager == null) {
            Toast.makeText(this, "USB менеджер не доступен", Toast.LENGTH_SHORT).show();
            return;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) {
            Toast.makeText(this, "Нет USB устройств", Toast.LENGTH_SHORT).show();
            return;
        }

        int requested = 0;
        for (UsbDevice device : deviceList.values()) {
            if (!usbManager.hasPermission(device)) {
                try {
                    android.app.PendingIntent permissionIntent = android.app.PendingIntent.getBroadcast(
                            this,
                            device.getDeviceId(),
                            new Intent("com.example.volumemonitor.USB_PERMISSION"),
                            android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    );
                    usbManager.requestPermission(device, permissionIntent);
                    requested++;
                    Log.d(TAG, "Запрос разрешения для: " + device.getDeviceName());
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка запроса разрешения: " + e.getMessage());
                }
            }
        }

        if (requested > 0) {
            Toast.makeText(this,
                    "Запросы отправлены для " + requested + " устройств",
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this,
                    "Все устройства уже имеют разрешения",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainActivity onResume");
        updateVolumeDisplay();
        updateUsbStatus();
        scanUsbDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainActivity onDestroy");

        // Отвязка от сервиса
        if (isBound) {
            try {
                unbindService(serviceConnection);
                isBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Ошибка отвязки от сервиса: " + e.getMessage());
            }
        }

        // Отмена регистрации Receiver'ов
        try {
            unregisterReceiver(volumeUpdateReceiver);
            unregisterReceiver(usbStatusReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка отмены регистрации Receiver'ов: " + e.getMessage());
        }
    }
}