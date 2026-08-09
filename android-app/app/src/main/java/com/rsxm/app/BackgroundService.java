package com.rsxm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 后台运行模式保活服务（前台服务）。
 *
 * 问题背景：proot/reasonix 环境是 app 进程的子进程，当用户用 adb 打开其他应用
 * （如微信）后本 Activity 退到后台，app 进程若无前台服务保护会被系统按 cached
 * 进程回收，整个 Linux 环境随之终止，无法在后台继续运行。
 *
 * 本服务在「后台运行模式」开启时启动：以前台服务（常驻通知）提升进程优先级，
 * 使系统在内存压力下也不会回收 app 进程，proot 环境即可在后台持续运行；
 * 关闭模式时由 MainActivity 调用 stopService 停止。
 *
 * 生命周期与 Activity 解耦：由开关控制（onCreate 时按 pref 恢复），
 * Activity 销毁不影响本服务，避免后台环境被误杀。
 */
public class BackgroundService extends Service {

    private static final String CHANNEL_ID = "rsxm_bg_mode";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 防御：系统按 START_STICKY 重建服务（intent == null）时校验开关状态，
        // 若用户已关闭后台运行模式则立即退出，避免服务无界常驻。
        if (intent == null && !getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("background_mode", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        // START_STICKY：进程存活时若服务被系统临时杀死，自动重建（前台服务重建
        // 由系统调度，不受后台启动前台服务限制）。
        return START_STICKY;
    }

    /** 创建通知渠道（Android 8.0+，minSdk 26 恒可用） */
    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("后台运行模式：保持 Reasonix Linux 环境在后台继续运行");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
    }

    /** 以前台服务方式启动（兼容 API 26-34+：类型参数 29+ 才有，specialUse 类型 34+ 才有） */
    private void startForegroundCompat() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Reasonix 后台运行中")
                .setContentText("Linux 环境正在后台继续运行（adb 打开其他应用不受影响）")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = (Build.VERSION.SDK_INT >= 34)
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
