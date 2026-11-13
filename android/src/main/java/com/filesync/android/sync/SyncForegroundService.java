package com.filesync.android.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.filesync.android.MainActivity;
import com.filesync.android.R;

/**
 * 前台服务：整个运行期常驻，防止被 MIUI / 澎湃 OS 在后台冻结或回收。
 *
 * 通知适配要点（澎湃 OS / MIUI 与原生 Android 表现一致化）：
 * - 独立渠道 IMPORTANCE_LOW + 关闭角标 + 无声无震动，避免“静默通知被折叠后看不到”
 * - 单色 vector 小图标，避免 HyperOS 上出现白色方块或系统图标
 * - setOnlyAlertOnce / setShowWhen(false) / setOngoing(true)，不会反复弹提示
 * - 点击通知直接回到 APP 主界面
 * - 文案随状态变化（运行中 / 正在同步 / 已完成），保证通知栏与 APP 内状态一致
 */
public class SyncForegroundService extends Service {

    public static final String ACTION_START = "com.filesync.START";
    public static final String ACTION_STOP = "com.filesync.STOP";
    public static final String ACTION_WAKE = "com.filesync.WAKE";

    public static final String CHANNEL_ID = "filesync_service_v2";
    public static final int NOTIFICATION_ID = 1001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        Notification notification = buildNotification(
                getString(R.string.sync_notification_text), -1);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            android.util.Log.e("FileSync", "启动前台服务失败", e);
        }
        // 被杀后由系统重启，重新拉起通知，避免通知栏里服务“消失”
        return START_STICKY;
    }

    private Notification buildNotification(String text, int percent) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.sync_notification_title))
                .setContentText(text)
                .setSubText(getString(R.string.sync_notification_subtext))
                .setSmallIcon(R.drawable.ic_stat_filesync)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF0F9D8F);

        if (percent >= 0) {
            builder.setProgress(100, Math.min(100, percent), false);
        } else {
            builder.setProgress(0, 0, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.sync_channel_desc));
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        manager.createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    /**
     * 更新通知文案（不影响前台服务状态）。服务未启动时静默失败。
     */
    public static void updateState(Context context, String text) {
        update(context, text, -1);
    }

    /**
     * 更新通知文案与进度。percent < 0 表示无进度条。
     */
    public static void updateProgress(Context context, String text, int percent) {
        update(context, text, Math.max(0, Math.min(100, percent)));
    }

    private static void update(Context context, String text, int percent) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.sync_notification_title))
                    .setContentText(text)
                    .setSubText(context.getString(R.string.sync_notification_subtext))
                    .setSmallIcon(R.drawable.ic_stat_filesync)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setColor(0xFF0F9D8F);
            if (percent >= 0) {
                builder.setProgress(100, percent, false);
            }
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            android.util.Log.e("FileSync", "更新通知失败", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
