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
        File external = appContext.getExternalFilesDir(null);
        if (external == null) {
            throw new IllegalStateException("外部存储不可用");
        }
        syncRoot = new File(external, "Sync");
        if (!syncRoot.exists() && !syncRoot.mkdirs()) {
            throw new IllegalStateException("无法创建同步目录");
        }

        String deviceId = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DEVICE_ID, deviceId).apply();
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
                postLog(action + "  " + relPath);
            }

            @Override
            public void onProgress(String relPath, long transferred, long total) {
                mainHandler.post(() -> {
                    for (StateListener l : stateListeners) {
                        l.onProgress(relPath, transferred, total);
                    }
                });
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
        notifySyncState(true);

        syncExecutor.submit(() -> {
            AndroidPeerClient client = new AndroidPeerClient(peer, okHttpClient);
            try {
                engine.runOnce(client);
            } catch (Exception e) {
                postLog("同步失败: " + e.getMessage());
            } finally {
                client.close();
                syncRunning = false;
                mainHandler.post(() -> notifySyncState(false));
            }
        });
    }

    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public List<DeviceInfo> getPeers() {
        return discovery == null ? java.util.Collections.emptyList() : discovery.getDevices();
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
