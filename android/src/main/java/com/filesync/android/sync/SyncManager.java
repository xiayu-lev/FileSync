package com.filesync.android.sync;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.filesync.common.discovery.DeviceDiscovery;
import com.filesync.common.discovery.DiscoveryListener;
import com.filesync.common.model.DeviceInfo;
import com.filesync.common.sync.SyncEngine;
import com.filesync.common.sync.SyncListener;
import com.filesync.android.net.AndroidHttpServer;
import com.filesync.android.net.AndroidPeerClient;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 单例同步管理器，统一持有发现、HTTP 服务、文件监听与同步引擎。
 */
public class SyncManager {

    private static final int DEFAULT_PORT = 18080;
    private static final int MAX_PORT_TRIES = 20;
    private static final String PREFS_NAME = "filesync_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SYNC_DIR = "sync_dir";

    private static volatile SyncManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<StateListener> stateListeners =
            new CopyOnWriteArrayList<>();

    private DeviceInfo self;
    private DeviceDiscovery discovery;
    private AndroidHttpServer httpServer;
    private RecursiveFileObserver fileObserver;
    private SyncEngine engine;
    private OkHttpClient okHttpClient;
    private WifiManager.MulticastLock multicastLock;

    private File syncRoot;
    private volatile boolean started;
    private volatile boolean syncRunning;
    private volatile int lastNotifiedPercent = -1;

    private SyncManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static SyncManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SyncManager.class) {
                if (instance == null) {
                    instance = new SyncManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized void init() throws Exception {
        syncRoot = resolveSyncRoot();

        String deviceId = prefs().getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs().edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        String deviceName = Build.MODEL + "的手机";
        self = new DeviceInfo(deviceId, deviceName, DeviceInfo.TYPE_ANDROID,
                "", DEFAULT_PORT, 1);

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        engine = new SyncEngine(syncRoot, self);
        engine.setListener(new SyncListener() {
            @Override
            public void onStart() {
                postLog("===== 开始同步 =====");
            }

            @Override
            public void onAction(String action, String relPath) {
                // 引擎写盘前先登记保护，避免 FileObserver 把自己的写入记成删除
                if (SyncEngine.ACTION_DOWNLOAD.equals(action)
                        || SyncEngine.ACTION_DELETE_LOCAL.equals(action)
                        || SyncEngine.ACTION_MKDIR.equals(action)) {
                    WriteGuard.guard(relPath);
                }
                postLog(action + "  " + relPath);
            }

            @Override
            public void onProgress(String relPath, long transferred, long total) {
                mainHandler.post(() -> {
                    for (StateListener l : stateListeners) {
                        l.onProgress(relPath, transferred, total);
                    }
                });
                if (total > 0) {
                    int percent = (int) Math.min(100, (transferred * 100) / total);
                    if (percent - lastNotifiedPercent >= 10) {
                        lastNotifiedPercent = percent;
                        SyncForegroundService.updateProgress(appContext,
                                appContext.getString(com.filesync.android.R.string
                                        .sync_notification_syncing)
                                        + " " + percent + "%", percent);
                    }
                }
            }

            @Override
            public void onActionDone(String action, String relPath) {
            }

            @Override
            public void onComplete(int changedCount) {
                postLog("===== 同步完成，变更 " + changedCount + " 项 =====");
            }

            @Override
            public void onError(String relPath, Exception error) {
                postLog("错误 [" + relPath + "]: " + error.getMessage());
            }
        });

        // HTTP 服务在 start() 阶段按端口可用性创建，此处不预先创建，
        // 否则会出现“未启动的旧实例 + 真正监听的实例”并存。
        httpServer = null;

        discovery = new DeviceDiscovery(self);
        discovery.setListener(new DiscoveryListener() {
            @Override
            public void onDeviceFound(DeviceInfo device) {
                postPeers();
            }

            @Override
            public void onDeviceUpdated(DeviceInfo device) {
                postPeers();
            }

            @Override
            public void onDeviceLost(DeviceInfo device) {
                postPeers();
                postLog("设备离线: " + device.getDeviceName());
            }
        });

        fileObserver = new RecursiveFileObserver(syncRoot);
        fileObserver.setListener(new FileEventListener() {
            @Override
            public void onChanged(String relPath) {
                // 第一版由手动按钮触发，自动同步可在此扩展
            }

            @Override
            public void onDeleted(String relPath) {
                try {
                    engine.recordLocalDelete(relPath);
                } catch (Exception e) {
                    postLog("墓碑记录失败: " + e.getMessage());
                }
            }
        });
    }

    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        // 先以前台服务身份常驻，避免被系统在后台回收（服务需持续监听对端请求）
        startForegroundService();
        acquireMulticastLock();
        startHttpWithFallback();
        android.util.Log.i("FileSync", "HTTP 服务已监听端口 " + self.getHttpPort());
        discovery.start();
        android.util.Log.i("FileSync", "UDP 发现已启动，running=" + discovery.isRunning());
        fileObserver.startWatching();
        started = true;
        postLog("本机服务已启动，同步目录: " + syncRoot.getAbsolutePath());
        SyncForegroundService.updateState(appContext,
                "运行中 · " + syncRoot.getName() + " · 端口 " + self.getHttpPort());
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (fileObserver != null) {
            fileObserver.stopWatching();
        }
        if (discovery != null) {
            discovery.stop();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        stopForegroundService();
    }

    public void syncWith(final DeviceInfo peer) {
        if (syncRunning) {
            postLog("已有同步任务在执行");
            return;
        }
        syncRunning = true;
        lastNotifiedPercent = -1;
        notifySyncState(true);
        SyncForegroundService.updateProgress(appContext,
                appContext.getString(com.filesync.android.R.string.sync_notification_syncing), 0);

        syncExecutor.submit(() -> {
            AndroidPeerClient client = new AndroidPeerClient(peer, okHttpClient);
            try {
                engine.runOnce(client);
            } catch (Exception e) {
                postLog("同步失败: " + e.getMessage());
            } finally {
                client.close();
                syncRunning = false;
                lastNotifiedPercent = -1;
                SyncForegroundService.updateState(appContext,
                        "运行中 · 与 " + peer.getDeviceName() + " 同步完成");
                mainHandler.post(() -> notifySyncState(false));
            }
        });
    }

    public void addStateListener(StateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * 切换同步目录：停掉监听/服务端 → 重新初始化 → 重新启动。
     * 目录必须可写，否则抛异常交给 UI 提示。
     */
    public synchronized void switchRoot(File newRoot) throws Exception {
        if (newRoot == null) {
            throw new IllegalArgumentException("目录为空");
        }
        File target = newRoot.getAbsoluteFile();
        if (!target.exists() && !target.mkdirs()) {
            throw new IllegalStateException("无法创建同步目录: " + target.getAbsolutePath());
        }
        if (!target.isDirectory()) {
            throw new IllegalStateException("不是有效目录: " + target.getAbsolutePath());
        }
        if (!target.canWrite()) {
            throw new IllegalStateException("目录不可写: " + target.getAbsolutePath());
        }
        boolean wasStarted = started;
        if (wasStarted) {
            stop();
        }
        prefs().edit().putString(KEY_SYNC_DIR, target.getAbsolutePath()).apply();
        syncRoot = target;
        init();
        start();
        postLog("同步目录已切换: " + target.getAbsolutePath());
    }

    /**
     * 恢复上次使用的目录；没有记录或不可用时回退到应用专属目录。
     */
    private File resolveSyncRoot() throws Exception {
        String saved = prefs().getString(KEY_SYNC_DIR, null);
        File dir = null;
        if (saved != null && !saved.trim().isEmpty()) {
            dir = new File(saved.trim());
        }
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            File external = appContext.getExternalFilesDir(null);
            if (external == null) {
                throw new IllegalStateException("外部存储不可用");
            }
            dir = new File(external, "Sync");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建同步目录");
            }
        }
        if (!dir.canWrite()) {
            // 记录目录已失效（例如被删除或权限被撤销），退回默认目录
            File external = appContext.getExternalFilesDir(null);
            if (external != null) {
                File fallback = new File(external, "Sync");
                if (fallback.exists() || fallback.mkdirs()) {
                    prefs().edit().putString(KEY_SYNC_DIR, fallback.getAbsolutePath()).apply();
                    return fallback;
                }
            }
            throw new IllegalStateException("同步目录不可写: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private android.content.SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<DeviceInfo> getPeers() {
        return discovery == null ? java.util.Collections.emptyList() : discovery.getDevices();
    }

    /** 手动刷新设备列表：立即再广播一次发现报文，让在线电脑马上回应 */
    public void refreshPeers() {
        if (discovery == null || !discovery.isRunning()) {
            postLog("设备发现未运行，无法刷新");
            return;
        }
        discovery.requestAnnounce();
        postLog("已刷新设备列表（当前在线 " + getPeers().size() + " 台）");
    }

    public File getSyncRoot() {
        return syncRoot;
    }

    public boolean isSyncRunning() {
        return syncRunning;
    }

    public boolean isStarted() {
        return started;
    }

    private void startHttpWithFallback() throws Exception {
        // 防御性处理：若已有实例仍在监听（重复调用 start 时），先释放，避免端口被自己占用后
        // 退到下一个端口，最终同一个进程里挂着两个服务端。
        if (httpServer != null && httpServer.wasStarted()) {
            return;
        }
        Exception lastError = null;
        for (int port = DEFAULT_PORT; port < DEFAULT_PORT + MAX_PORT_TRIES; port++) {
            try {
                AndroidHttpServer srv = new AndroidHttpServer(port, self, syncRoot,
                        engine.tombstoneStore());
                // 对端同步时把日志推过来，这里转成界面日志行
                srv.setLogListener((from, text) -> postLog("[来自 " + from + "] " + text));
                srv.start();
                this.httpServer = srv;
                self.setHttpPort(port);
                return;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("HTTP 服务端口不可用", lastError);
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("filesync-multicast");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private void startForegroundService() {
        Intent intent = new Intent(appContext, SyncForegroundService.class);
        intent.setAction(SyncForegroundService.ACTION_START);
        ContextCompat.startForegroundService(appContext, intent);
    }

    private void stopForegroundService() {
        Intent intent = new Intent(appContext, SyncForegroundService.class);
        intent.setAction(SyncForegroundService.ACTION_STOP);
        appContext.startService(intent);
    }

    private void postPeers() {
        mainHandler.post(() -> {
            List<DeviceInfo> peers = getPeers();
            for (StateListener l : stateListeners) {
                l.onPeersChanged(peers);
            }
        });
    }

    private void postLog(String message) {
        mainHandler.post(() -> {
            for (StateListener l : stateListeners) {
                l.onLog(message);
            }
        });
    }

    private void notifySyncState(boolean running) {
        for (StateListener l : stateListeners) {
            l.onSyncStateChanged(running);
        }
    }

    /**
     * UI 状态回调，全部在主线程触发。
     */
    public interface StateListener {
        void onPeersChanged(List<DeviceInfo> peers);

        void onLog(String message);

        void onProgress(String relPath, long transferred, long total);

        void onSyncStateChanged(boolean running);
    }
}
