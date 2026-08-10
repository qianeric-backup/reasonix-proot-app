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
 * ADB 无线调试保活服务（前台服务）。
 *
 * 问题背景：adb 无线调试运行在 Linux 环境（proot，app 进程的子进程）内。
 * 用户从最近任务滑动关闭应用时，若无前台服务保护，app 进程会被系统回收，
 * proot 环境随之终止，guest 内 adb 断开 → 无线调试连接自行关闭。
 *
 * 本服务在「ADB 无线调试」面板点击自动连接/配对时启动：以前台服务（常驻通知）
 * 提升进程优先级，使滑动关闭应用后进程不被回收，proot 与 guest 内 adb 持续
 * 运行，无线调试连接保持。面板「停止 ADB 后台保活」时由 MainActivity 停止。
 *
 * 生命周期与 Activity 解耦：由 ADB 保活开关控制，Activity 销毁不影响本服务。
 */
public class AdbKeepAliveService extends Service {

    private static final String CHANNEL_ID = "rsxm_adb_keepalive";
    private static final int NOTIFICATION_ID = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 防御：系统按 START_STICKY 重建服务（intent == null）时校验开关状态，
        // 若用户已关闭 ADB 保活则立即退出，避免服务无界常驻。
        if (intent == null && !getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("adb_keepalive", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        return START_STICKY;
    }

    /** 创建通知渠道（Android 8.0+，minSdk 26 恒可用） */
    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ADB 保活", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("ADB 无线调试：应用关闭后连接保持");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
    }

    /** 以前台服务方式启动（兼容 API 26-34+：类型参数 29+ 才有，specialUse 类型 34+ 才有） */
    private void startForegroundCompat() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("ADB 无线调试保持中")
                .setContentText("关闭应用后 adb 连接保持，点此进入应用可停止")
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
