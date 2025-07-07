package com.example.volumemonitor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Запускаем сервис мониторинга громкости
        startService(new Intent(this, VolumeMonitorService.class));
        // Закрываем активити, оставляя сервис работать в фоне
        finish();
    }
}