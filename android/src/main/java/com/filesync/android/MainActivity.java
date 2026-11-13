package com.filesync.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.filesync.android.sync.SyncManager;
import com.filesync.common.model.DeviceInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Android 端主界面。
 */
public class MainActivity extends AppCompatActivity implements SyncManager.StateListener {

    private static final int REQ_NOTIFICATION = 200;
    private static final int REQ_STORAGE = 201;

    private TextView tvDir;
    private TextView tvLog;
    private TextView tvProgress;
    private TextView tvSelected;
    private ListView lvPeers;
    private Button btnSync;
    private Button btnDir;
    private Button btnRefresh;
    private ProgressBar progressBar;
    private ScrollView logScroll;

    private SyncManager manager;
    private PeerAdapter peerAdapter;
    private final List<DeviceInfo> peers = new ArrayList<>();

    /** 记住用户选中的设备 ID：列表刷新（设备上下线）后仍保持同一台被选中 */
    private String selectedDeviceId;

    /** 授权完成后自动继续打开目录选择器 */
    private boolean pickDirAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDir = findViewById(R.id.tv_dir);
        tvLog = findViewById(R.id.tv_log);
        tvProgress = findViewById(R.id.tv_progress);
        tvSelected = findViewById(R.id.tv_selected);
        lvPeers = findViewById(R.id.lv_peers);
        btnSync = findViewById(R.id.btn_sync);
        btnDir = findViewById(R.id.btn_dir);
        btnRefresh = findViewById(R.id.btn_refresh);
        progressBar = findViewById(R.id.progress);
        logScroll = findViewById(R.id.log_scroll);

        peerAdapter = new PeerAdapter();
        lvPeers.setAdapter(peerAdapter);
        lvPeers.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        btnSync.setOnClickListener(v -> onSyncClicked());
        btnDir.setOnClickListener(v -> onDirClicked());
        btnRefresh.setOnClickListener(v -> {
            if (manager != null) {
                manager.refreshPeers();
            }
        });
        lvPeers.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < peers.size()) {
                selectedDeviceId = peers.get(position).getDeviceId();
                updateSelectedLabel();
            }
        });

        requestNotificationPermissionIfNeeded();
        bootstrap();
    }

    private void bootstrap() {
        new Thread(() -> {
            try {
                manager = SyncManager.getInstance(this);
                manager.addStateListener(this);
                if (manager.isStarted()) {
                    // 界面重建（例如从后台返回、配置变化）时不要重复初始化，避免重复监听端口
                    runOnUiThread(() -> {
                        updateDirLabel();
                        appendLog("服务已在运行");
                        peers.clear();
                        peers.addAll(manager.getPeers());
                        peerAdapter.notifyDataSetChanged();
                        restoreSelection();
                    });
                    return;
                }
                manager.init();
                manager.start();
                runOnUiThread(() -> {
                    updateDirLabel();
                    appendLog("服务初始化完成");
                });
            } catch (Exception e) {
                android.util.Log.e("FileSync", "初始化失败", e);
                runOnUiThread(() -> appendLog("初始化失败: " + e.getMessage()));
            }
        }, "fs-bootstrap").start();
    }

    private void updateDirLabel() {
        if (manager != null && manager.getSyncRoot() != null) {
            tvDir.setText(manager.getSyncRoot().getAbsolutePath());
        }
    }

    private void onSyncClicked() {
        if (manager == null) {
            appendLog("服务尚未就绪");
            return;
        }
        DeviceInfo target = findSelectedPeer();
        if (target == null) {
            appendLog("请先在列表中点击选择一台设备");
            return;
        }
        manager.syncWith(target);
    }

    /** 按记住的设备 ID 找到当前选中的设备；设备已离线则返回 null */
    private DeviceInfo findSelectedPeer() {
        if (selectedDeviceId != null) {
            for (DeviceInfo d : peers) {
                if (selectedDeviceId.equals(d.getDeviceId())) {
                    return d;
                }
            }
        }
        int pos = lvPeers.getCheckedItemPosition();
        if (pos >= 0 && pos < peers.size()) {
            return peers.get(pos);
        }
        return null;
    }

    private void updateSelectedLabel() {
        if (tvSelected == null) {
            return;
        }
        DeviceInfo d = findSelectedPeer();
        if (d == null) {
            tvSelected.setText("当前选中：未选择（点击列表中的设备）");
            return;
        }
        String tag = DeviceInfo.TYPE_ANDROID.equals(d.getDeviceType()) ? "[手机] " : "[电脑] ";
        tvSelected.setText("当前选中：" + tag + d.getDeviceName()
                + "\n" + d.getIp() + ":" + d.getHttpPort());
    }

    /** 列表刷新后按设备 ID 恢复选中状态 */
    private void restoreSelection() {
        lvPeers.clearChoices();
        if (selectedDeviceId == null) {
            updateSelectedLabel();
            return;
        }
        for (int i = 0; i < peers.size(); i++) {
            if (selectedDeviceId.equals(peers.get(i).getDeviceId())) {
                lvPeers.setItemChecked(i, true);
                break;
            }
        }
        updateSelectedLabel();
    }

    // ==================== 同步目录切换 ====================

    private void onDirClicked() {
        if (manager == null) {
            Toast.makeText(this, "服务尚未就绪，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasStorageAccess()) {
            pickDirAfterPermission = true;
            showPermissionDialog();
            return;
        }
        File start = manager.getSyncRoot();
        if (start == null || !start.isDirectory()) {
            start = Environment.getExternalStorageDirectory();
        }
        showDirPicker(start);
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_message)
                .setPositiveButton(R.string.perm_go, (d, w) -> openAllFilesAccessSettings())
                .setNegativeButton(R.string.perm_cancel, (d, w) -> pickDirAfterPermission = false)
                .show();
    }

    private void openAllFilesAccessSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                Toast.makeText(this, "请手动到 设置 → 应用管理 → FileSync 授权",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && pickDirAfterPermission && hasStorageAccess()) {
            pickDirAfterPermission = false;
            File start = manager != null && manager.getSyncRoot() != null
                    ? manager.getSyncRoot() : Environment.getExternalStorageDirectory();
            showDirPicker(start);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pickDirAfterPermission && hasStorageAccess()) {
            pickDirAfterPermission = false;
            File start = manager != null && manager.getSyncRoot() != null
                    ? manager.getSyncRoot() : Environment.getExternalStorageDirectory();
            showDirPicker(start);
        }
    }

    /**
     * 内置目录浏览器：逐级进入子目录，点“使用此目录”即切换。
     */
    private void showDirPicker(File dir) {
        File arg = dir;
        if (arg == null || !arg.isDirectory()) {
            arg = Environment.getExternalStorageDirectory();
        }
        final File current = arg;
        File[] raw = current.listFiles(File::isDirectory);
        List<File> dirs = new ArrayList<>();
        if (raw != null) {
            Arrays.sort(raw, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            dirs.addAll(Arrays.asList(raw));
        }

        boolean hasParent = current.getParentFile() != null;
        String[] items;
        List<Object> targets = new ArrayList<>();
        if (hasParent) {
            items = new String[dirs.size() + 1];
            items[0] = getString(R.string.dir_picker_parent);
            targets.add(current.getParentFile());
            for (int i = 0; i < dirs.size(); i++) {
                items[i + 1] = dirs.get(i).getName() + "/";
                targets.add(dirs.get(i));
            }
        } else {
            items = new String[dirs.size()];
            for (int i = 0; i < dirs.size(); i++) {
                items[i] = dirs.get(i).getName() + "/";
                targets.add(dirs.get(i));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dir_picker_title) + "\n" + current.getAbsolutePath())
                .setPositiveButton(R.string.dir_picker_use, (d, w) -> applySyncDir(current))
                .setNegativeButton(android.R.string.cancel, null);
        if (items.length == 0) {
            builder.setMessage("该目录下没有子目录，可直接使用此目录");
        } else {
            builder.setItems(items, (d, which) -> showDirPicker((File) targets.get(which)));
        }
        builder.show();
    }

    private void applySyncDir(File dir) {
        appendLog("切换到目录: " + dir.getAbsolutePath());
        new Thread(() -> {
            try {
                manager.switchRoot(dir);
                runOnUiThread(() -> {
                    updateDirLabel();
                    peers.clear();
                    peers.addAll(manager.getPeers());
                    peerAdapter.notifyDataSetChanged();
                    restoreSelection();
                    Toast.makeText(this, "同步目录已切换", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                android.util.Log.e("FileSync", "切换目录失败", e);
                runOnUiThread(() -> {
                    appendLog("切换目录失败: " + e.getMessage());
                    Toast.makeText(this, "切换失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "fs-switch-dir").start();
    }

    // ==================== 状态回调 ====================

    @Override
    public void onPeersChanged(List<DeviceInfo> updated) {
        peers.clear();
        peers.addAll(updated);
        peerAdapter.notifyDataSetChanged();
        // 设备上下线导致列表重建时，保持原来选中的设备（按设备 ID 还原）
        restoreSelection();
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
