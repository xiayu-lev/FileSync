package com.filesync.android;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.filesync.android.sync.SyncManager;
import com.filesync.common.model.DeviceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Android 端主界面。
 */
public class MainActivity extends AppCompatActivity implements SyncManager.StateListener {

    private static final int REQ_NOTIFICATION = 200;

    private TextView tvDir;
    private TextView tvLog;
    private TextView tvProgress;
    private ListView lvPeers;
    private Button btnSync;
    private ProgressBar progressBar;
    private ScrollView logScroll;

    private SyncManager manager;
    private PeerAdapter peerAdapter;
    private final List<DeviceInfo> peers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDir = findViewById(R.id.tv_dir);
        tvLog = findViewById(R.id.tv_log);
        tvProgress = findViewById(R.id.tv_progress);
        lvPeers = findViewById(R.id.lv_peers);
        btnSync = findViewById(R.id.btn_sync);
        progressBar = findViewById(R.id.progress);
        logScroll = findViewById(R.id.log_scroll);

        peerAdapter = new PeerAdapter();
        lvPeers.setAdapter(peerAdapter);
        lvPeers.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        btnSync.setOnClickListener(v -> onSyncClicked());

        requestNotificationPermissionIfNeeded();
        bootstrap();
    }

    private void bootstrap() {
        new Thread(() -> {
            try {
                manager = SyncManager.getInstance(this);
                if (manager.isStarted()) {
                    // 界面重建（例如从后台返回、配置变化）时不要重复初始化，避免重复监听端口
                    runOnUiThread(() -> {
                        tvDir.setText(manager.getSyncRoot().getAbsolutePath());
                        appendLog("服务已在运行");
                        peers.clear();
                        peers.addAll(manager.getPeers());
                        peerAdapter.notifyDataSetChanged();
                    });
                    return;
                }
                manager.init();
                manager.addStateListener(this);
                manager.start();
                runOnUiThread(() -> {
                    tvDir.setText(manager.getSyncRoot().getAbsolutePath());
                    appendLog("服务初始化完成");
                });
            } catch (Exception e) {
                android.util.Log.e("FileSync", "初始化失败", e);
                runOnUiThread(() -> appendLog("初始化失败: " + e.getMessage()));
            }
        }, "fs-bootstrap").start();
    }

    private void onSyncClicked() {
        if (manager == null) {
            appendLog("服务尚未就绪");
            return;
        }
        int pos = lvPeers.getCheckedItemPosition();
        if (pos < 0 || pos >= peers.size()) {
            appendLog("请先在列表中点击选择一台设备");
            return;
        }
        manager.syncWith(peers.get(pos));
    }

    @Override
    public void onPeersChanged(List<DeviceInfo> updated) {
        peers.clear();
        peers.addAll(updated);
        peerAdapter.notifyDataSetChanged();
    }

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    @Override
    public void onProgress(String relPath, long transferred, long total) {
        runOnUiThread(() -> {
            if (total > 0) {
                int percent = (int) Math.min(100, (transferred * 100) / total);
                progressBar.setProgress(percent);
                tvProgress.setText(relPath + "  " + percent + "%");
            }
        });
    }

    @Override
    public void onSyncStateChanged(boolean running) {
        btnSync.setEnabled(!running);
        btnSync.setText(running ? "同步中..." : "立即同步");
        if (!running) {
            progressBar.setProgress(0);
            tvProgress.setText("空闲");
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            tvLog.append(message + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    @Override
    protected void onDestroy() {
        if (manager != null) {
            manager.removeStateListener(this);
            if (isFinishing()) {
                manager.stop();
            }
        }
        super.onDestroy();
    }

    /**
     * 设备列表适配器。
     */
    private final class PeerAdapter extends ArrayAdapter<DeviceInfo> {

        PeerAdapter() {
            super(MainActivity.this, R.layout.item_device, peers);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_device, parent, false);
            }
            DeviceInfo d = getItem(position);
            TextView name = view.findViewById(R.id.tv_name);
            TextView addr = view.findViewById(R.id.tv_addr);
            String tag = DeviceInfo.TYPE_ANDROID.equals(d.getDeviceType())
                    ? "[手机] " : "[电脑] ";
            name.setText(tag + d.getDeviceName());
            addr.setText(d.getIp() + ":" + d.getHttpPort());
            return view;
        }
    }
}
