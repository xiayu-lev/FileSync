package com.filesync.desktop;

import com.filesync.common.discovery.DeviceDiscovery;
import com.filesync.common.discovery.DiscoveryListener;
import com.filesync.common.model.DeviceInfo;
import com.filesync.common.sync.SyncEngine;
import com.filesync.common.sync.SyncListener;
import com.filesync.common.sync.SyncSummary;
import com.filesync.desktop.net.DesktopPeerClient;
import com.filesync.desktop.net.HttpFileServer;
import com.filesync.desktop.sync.DirectoryWatcher;
import com.filesync.desktop.sync.DirectoryWatchListener;
import com.filesync.desktop.sync.WriteGuard;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JavaFX 桌面端入口。
 */
public class MainApp extends Application {

    private static final int DEFAULT_PORT = 18080;
    private static final int MAX_PORT_TRIES = 20;
    private static final long AUTO_SYNC_MIN_GAP_MS = 3000L;

    private final ObservableList<DeviceInfo> peerList = FXCollections.observableArrayList();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fs-sync-worker");
        t.setDaemon(true);
        return t;
    });

    private ListView<DeviceInfo> deviceListView;
    private Label selectedLabel;
    private TextField dirField;
    private Button chooseDirBtn;
    private Button syncBtn;
    private Button cancelBtn;
    private CheckBox autoSyncBox;
    private CheckBox autoStartBox;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Label statusLabel;
    private TextArea logArea;

    private AppConfig config;
    private DeviceInfo self;
    private DeviceDiscovery discovery;
    private HttpFileServer fileServer;
    private DirectoryWatcher watcher;
    private SyncEngine engine;

    private TrayIcon trayIcon;
    private boolean trayHintShown;
    private boolean reallyExit;
    private boolean shutdownDone;

    private volatile File syncRoot;
    private volatile boolean syncRunning;
    private final AtomicLong lastSyncAt = new AtomicLong(0L);

    /** 日志缓冲：后台线程写入缓冲，FX 线程按批刷新，避免逐行 runLater 卡住界面 */
    private final StringBuilder logPending = new StringBuilder();
    private boolean logFlushScheduled;
    private static final int MAX_LOG_CHARS = 300_000;
    private static final int KEEP_LOG_CHARS = 120_000;

    @Override
    public void start(Stage stage) {
        try {
            config = new AppConfig();
            config.load();
        } catch (Exception e) {
            fatalError("配置加载失败", e);
            return;
        }

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setLeft(buildDevicePanel());
        root.setCenter(buildSyncPanel());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(getClass().getResource("/desktop.css").toExternalForm());
        // 窗口/任务栏图标与手机端、安装包保持同一套图标
        try {
            stage.getIcons().add(new Image(getClass().getResource("/app-icon.png").toExternalForm()));
        } catch (Exception ignored) {
            // 图标缺失不影响功能
        }
        stage.setTitle("FileSync 文件同步 - 桌面端");
        stage.setScene(scene);

        // 关闭窗口不退出程序：隐藏到系统托盘，后台同步继续进行
        Platform.setImplicitExit(false);
        setupTray(stage);
        stage.setOnCloseRequest(e -> {
            if (reallyExit) {
                return;
            }
            e.consume();
            minimizeToTray(stage);
        });

        stage.show();
        applyAutoStart();

        bootstrapNetwork();
        restoreLastDirectory();
    }

    private VBox buildTopBar() {
        Label dirLabel = new Label("同步目录");
        dirField = new TextField();
        dirField.setPromptText("请选择需要同步的文件夹");
        dirField.setEditable(false);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        chooseDirBtn = new Button("选择目录");
        chooseDirBtn.setOnAction(e -> chooseDirectory());

        HBox row = new HBox(10, dirLabel, dirField, chooseDirBtn);
        row.setPadding(new Insets(14, 16, 10, 16));
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(row);
        box.getStyleClass().add("top-bar");
        return box;
    }

    private VBox buildDevicePanel() {
        Label title = new Label("局域网在线设备");
        title.getStyleClass().add("panel-title");

        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> refreshDevices());

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        deviceListView = new ListView<>(peerList);
        deviceListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DeviceInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String tag = DeviceInfo.TYPE_ANDROID.equals(item.getDeviceType())
                            ? "[手机] " : "[电脑] ";
                    setText(tag + item.getDeviceName() + "\n" + item.getIp()
                            + ":" + item.getHttpPort());
                }
            }
        });
        VBox.setVgrow(deviceListView, Priority.ALWAYS);

        // 明确显示当前选中哪台设备：列表刷新后仍然保持选中，这里同步更新文案
        selectedLabel = new Label("当前选中：未选择");
        selectedLabel.getStyleClass().add("selected-label");
        selectedLabel.setWrapText(true);
        deviceListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldV, newV) -> updateSelectedLabel());

        Label hint = new Label("设备需与本机连接同一 WiFi/路由器");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");

        VBox box = new VBox(8, header, deviceListView, selectedLabel, hint);
        box.setPadding(new Insets(12));
        box.setPrefWidth(270);
        box.getStyleClass().add("side-panel");
        return box;
    }

    /** 手动刷新设备列表：立即再广播一次发现报文，已选中的设备保持不变 */
    private void refreshDevices() {
        if (discovery == null || !discovery.isRunning()) {
            log("设备发现未运行，无法刷新");
            return;
        }
        discovery.requestAnnounce();
        log("已刷新设备列表（当前在线 " + peerList.size() + " 台）");
        updateSelectedLabel();
    }

    private void updateSelectedLabel() {
        if (selectedLabel == null || deviceListView == null) {
            return;
        }
        DeviceInfo sel = deviceListView.getSelectionModel().getSelectedItem();
        if (sel == null) {
            selectedLabel.setText("当前选中：未选择");
            return;
        }
        String tag = DeviceInfo.TYPE_ANDROID.equals(sel.getDeviceType()) ? "[手机] " : "[电脑] ";
        selectedLabel.setText("当前选中：" + tag + sel.getDeviceName()
                + "\n" + sel.getIp() + ":" + sel.getHttpPort());
    }

    private VBox buildSyncPanel() {
        syncBtn = new Button("立即同步");
        syncBtn.getStyleClass().add("primary-button");
        syncBtn.setOnAction(e -> triggerSync());

        cancelBtn = new Button("停止");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> {
            if (engine != null) {
                engine.cancel();
            }
        });

        autoSyncBox = new CheckBox("自动同步（目录变化时自动执行）");

        autoStartBox = new CheckBox("开机自启");
        autoStartBox.setSelected(config.isAutoStart());
        autoStartBox.setTooltip(new Tooltip("Windows 登录后自动启动 FileSync"));
        autoStartBox.selectedProperty().addListener((obs, oldV, newV) -> {
            if (config == null) {
                return;
            }
            config.setAutoStart(newV);
            try {
                config.save();
            } catch (Exception e) {
                log("保存开机自启设置失败: " + e.getMessage());
            }
            if (!AutoStart.setEnabled(newV)) {
                log(newV ? "开机自启设置失败" : "关闭开机自启失败");
                return;
            }
            log(newV ? "已开启开机自启" : "已关闭开机自启");
        });

        HBox buttonRow = new HBox(10, syncBtn, cancelBtn, autoSyncBox, autoStartBox);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        progressLabel = new Label("空闲");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);

        VBox progressBox = new VBox(6, progressLabel, progressBar);
        progressBox.setPadding(new Insets(12, 0, 12, 0));

        Separator separator = new Separator();

        Label logTitle = new Label("同步日志");
        logTitle.getStyleClass().add("panel-title");
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox box = new VBox(10, buttonRow, progressBox, separator, logTitle, logArea);
        box.setPadding(new Insets(14, 16, 14, 16));
        return box;
    }

    private HBox buildStatusBar() {
        statusLabel = new Label("正在初始化...");
        statusLabel.getStyleClass().add("hint");
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    /** 创建系统托盘图标：关闭主窗口后仍能从这里恢复或退出 */
    private void setupTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            BufferedImage icon = ImageIO.read(getClass().getResource("/app-icon.png"));
            PopupMenu popup = new PopupMenu();
            MenuItem showItem = new MenuItem("显示主界面");
            showItem.addActionListener(e -> Platform.runLater(() -> showStage(stage)));
            MenuItem exitItem = new MenuItem("退出 FileSync");
            exitItem.addActionListener(e -> Platform.runLater(this::exitApplication));
            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(icon, "FileSync 文件同步", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> showStage(stage)));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            trayIcon = null;
            log("系统托盘不可用，关闭窗口将最小化到任务栏: " + e.getMessage());
        }
    }

    private void minimizeToTray(Stage stage) {
        if (trayIcon == null) {
            stage.setIconified(true);
            return;
        }
        stage.hide();
        if (!trayHintShown) {
            trayIcon.displayMessage("FileSync 文件同步",
                    "已最小化到系统托盘，双击图标可恢复；后台同步会继续运行。",
                    TrayIcon.MessageType.INFO);
            trayHintShown = true;
        }
    }

    private void showStage(Stage stage) {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.setIconified(false);
        stage.toFront();
    }

    /** 启动时按配置写入或删除开机自启项，安装路径变化时自动覆盖旧路径 */
    private void applyAutoStart() {
        if (autoStartBox == null) {
            return;
        }
        boolean available = AutoStart.installedLauncher() != null;
        autoStartBox.setDisable(!available);
        if (!available) {
            autoStartBox.setTooltip(new Tooltip("开发运行模式不注册开机自启；安装版可用"));
            return;
        }
        if (AutoStart.setEnabled(config.isAutoStart())) {
            log(config.isAutoStart()
                    ? "已启用开机自启（Windows 登录后自动运行）"
                    : "开机自启已关闭");
        } else {
            log("开机自启设置失败");
        }
    }

    private void exitApplication() {
        reallyExit = true;
        shutdown();
        Platform.exit();
        System.exit(0);
    }

    private void bootstrapNetwork() {
        self = new DeviceInfo(config.getDeviceId(), config.getDeviceName(),
                DeviceInfo.TYPE_DESKTOP, "", DEFAULT_PORT, 1);

        // HTTP 服务启动，端口被占用则递增
        int port = startServerWithFallback();
        self.setHttpPort(port);

        try {
            discovery = new DeviceDiscovery(self);
            discovery.setListener(new DiscoveryListener() {
                @Override
                public void onDeviceFound(DeviceInfo device) {
                    Platform.runLater(() -> upsertPeer(device));
                }

                @Override
                public void onDeviceUpdated(DeviceInfo device) {
                    Platform.runLater(() -> upsertPeer(device));
                }

                @Override
                public void onDeviceLost(DeviceInfo device) {
                    Platform.runLater(() -> refreshKeepsSelection(() -> {
                        peerList.removeIf(d -> d.getDeviceId().equals(device.getDeviceId()));
                        log("设备离线: " + device.getDeviceName());
                    }));
                }
            });
            discovery.start();
            statusLabel.setText("运行中 | 本机: " + config.getDeviceName()
                    + " | 服务端口: " + port + " | 等待设备接入");
            log("本机服务已启动，监听端口 " + port);
        } catch (Exception e) {
            log("设备发现启动失败: " + e.getMessage());
        }
    }

    private int startServerWithFallback() {
        Exception lastError = null;
        for (int port = DEFAULT_PORT; port < DEFAULT_PORT + MAX_PORT_TRIES; port++) {
            try {
                DeviceInfo candidate = new DeviceInfo(self.getDeviceId(), self.getDeviceName(),
                        self.getDeviceType(), "", port, 1);
                HttpFileServer srv = new HttpFileServer(candidate, null, null);
                srv.setLogListener(this::log);
                srv.start();
                this.fileServer = srv;
                return port;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("无法在 " + DEFAULT_PORT + "~"
                + (DEFAULT_PORT + MAX_PORT_TRIES - 1) + " 找到可用端口", lastError);
    }

    private void restoreLastDirectory() {
        String last = config.getLastSyncDir();
        if (last != null && !last.isEmpty() && new File(last).isDirectory()) {
            bindSyncDirectory(new File(last));
        }
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择同步目录");
        if (syncRoot != null) {
            chooser.setInitialDirectory(syncRoot);
        }
        File selected = chooser.showDialog(chooseDirBtn.getScene().getWindow());
        if (selected != null) {
            bindSyncDirectory(selected);
        }
    }

    private void bindSyncDirectory(File directory) {
        try {
            if (watcher != null) {
                watcher.stop();
            }
            syncRoot = directory;
            dirField.setText(directory.getAbsolutePath());
            config.setLastSyncDir(directory.getAbsolutePath());
            config.save();

            // 墓碑等状态写进 %USERPROFILE%\.filesync-desktop\state-*，不污染用户选择的同步目录
            File stateBase = new File(System.getProperty("user.home"), ".filesync-desktop");
            engine = new SyncEngine(syncRoot, self, stateBase);
            engine.setListener(buildSyncListener());

            if (fileServer != null) {
                fileServer.setSyncRoot(syncRoot);
                fileServer.setTombstoneStore(engine.tombstoneStore());
            }

            watcher = new DirectoryWatcher();
            watcher.setListener(new DirectoryWatchListener() {
                @Override
                public void onChanged(String relPath) {
                    if (autoSyncBox.isSelected()) {
                        scheduleAutoSync();
                    }
                }

                @Override
                public void onDeleted(String relPath) {
                    // 同步引擎自己写盘（先 .part 再 rename）会触发删除事件，
                    // 这类路径不记墓碑，否则下一轮会把刚收到的文件当删除处理
                    if (WriteGuard.isGuarded(relPath)) {
                        return;
                    }
                    try {
                        if (engine != null) {
                            engine.recordLocalDelete(relPath);
                        }
                    } catch (Exception e) {
                        log("墓碑记录失败: " + e.getMessage());
                    }
                }
            });
            watcher.start(directory.toPath());
            log("已绑定同步目录: " + directory.getAbsolutePath());
        } catch (Exception e) {
            log("目录绑定失败: " + e.getMessage());
        }
    }

    private void scheduleAutoSync() {
        long now = System.currentTimeMillis();
        if (now - lastSyncAt.get() < AUTO_SYNC_MIN_GAP_MS) {
            return;
        }
        triggerSync();
    }

    private void triggerSync() {
        if (syncRunning) {
            return;
        }
        if (syncRoot == null || engine == null) {
            log("请先选择同步目录");
            return;
        }
        DeviceInfo peer = deviceListView.getSelectionModel().getSelectedItem();
        if (peer == null) {
            log("请先在左侧选择要同步的设备");
            return;
        }

        syncRunning = true;
        lastSyncAt.set(System.currentTimeMillis());
        syncBtn.setDisable(true);
        cancelBtn.setDisable(false);
        progressBar.setProgress(0);

        syncExecutor.submit(() -> {
            DesktopPeerClient client = new DesktopPeerClient(peer);
            try {
                engine.runOnce(client);
            } catch (Exception e) {
                Platform.runLater(() -> log("同步失败: " + e.getMessage()));
            } finally {
                client.close();
                Platform.runLater(() -> {
                    syncRunning = false;
                    syncBtn.setDisable(false);
                    cancelBtn.setDisable(true);
                    progressLabel.setText("空闲");
                    progressBar.setProgress(0);
                });
            }
        });
    }

    private SyncListener buildSyncListener() {
        return new SyncListener() {
            @Override
            public void onStart() {
                Platform.runLater(() -> {
                    progressLabel.setText("正在同步...");
                    log("===== 开始同步 =====");
                });
            }

            @Override
            public void onAction(String action, String relPath) {
                // 引擎即将写盘，先登记保护，避免监听器把自己的写入记成删除
                if (SyncEngine.ACTION_DOWNLOAD.equals(action)
                        || SyncEngine.ACTION_DELETE_LOCAL.equals(action)
                        || SyncEngine.ACTION_MKDIR.equals(action)) {
                    WriteGuard.guard(relPath);
                }
                Platform.runLater(() -> log(action + "  " + relPath));
            }

            @Override
            public void onProgress(String relPath, long transferred, long total) {
                if (total <= 0) {
                    return;
                }
                Platform.runLater(() -> {
                    progressBar.setProgress((double) transferred / total);
                    progressLabel.setText(relPath + "  "
                            + (transferred / 1024) + " / " + (total / 1024) + " KB");
                });
            }

            @Override
            public void onActionDone(String action, String relPath) {
                // 动作完成由日志体现，无需额外处理
            }

            @Override
            public void onComplete(SyncSummary summary) {
                Platform.runLater(() -> log(summary.describe()));
            }

            @Override
            public void onError(String relPath, Exception error) {
                Platform.runLater(() -> log("错误 [" + relPath + "]: " + error.getMessage()));
            }
        };
    }

    private void upsertPeer(DeviceInfo device) {
        // 定时发现会不断刷新列表，按设备 ID 恢复选中，避免用户刚点好的设备又被清空
        refreshKeepsSelection(() -> {
            peerList.removeIf(d -> d.getDeviceId().equals(device.getDeviceId()));
            peerList.add(device);
        });
    }

    /** 执行列表变更，保持用户选中的设备；没有选中项时自动选第一台在线设备 */
    private void refreshKeepsSelection(Runnable mutation) {
        if (deviceListView == null) {
            mutation.run();
            return;
        }
        DeviceInfo selected = deviceListView.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? null : selected.getDeviceId();
        mutation.run();
        if (selectedId != null) {
            for (DeviceInfo d : peerList) {
                if (selectedId.equals(d.getDeviceId())) {
                    deviceListView.getSelectionModel().select(d);
                    updateSelectedLabel();
                    return;
                }
            }
        }
        if (!peerList.isEmpty()) {
            DeviceInfo first = peerList.get(0);
            deviceListView.getSelectionModel().select(first);
            updateSelectedLabel();
            String tag = DeviceInfo.TYPE_ANDROID.equals(first.getDeviceType())
                    ? "[手机] " : "[电脑] ";
            log((selectedId == null ? "已自动选择设备: " : "原选中设备已离线，自动切换到: ")
                    + tag + first.getDeviceName());
            return;
        }
        updateSelectedLabel();
    }

    private void log(String message) {
        if (logArea == null) {
            System.out.println(LocalTime.now().withNano(0) + "  " + message);
            return;
        }
        String line = LocalTime.now().withNano(0) + "  " + message;
        synchronized (logPending) {
            logPending.append(line).append('\n');
        }
        if (Platform.isFxApplicationThread()) {
            flushLog();
        } else if (!logFlushScheduled) {
            logFlushScheduled = true;
            Platform.runLater(this::flushLog);
        }
    }

    /** 把缓冲里的日志一次性追加到日志框并滚到底部 */
    private void flushLog() {
        logFlushScheduled = false;
        String chunk;
        synchronized (logPending) {
            if (logPending.length() == 0) {
                return;
            }
            chunk = logPending.toString();
            logPending.setLength(0);
        }
        if (logArea == null) {
            return;
        }
        if (logArea.getLength() + chunk.length() > MAX_LOG_CHARS) {
            int cut = Math.max(0, logArea.getLength() + chunk.length() - KEEP_LOG_CHARS);
            if (cut < logArea.getLength()) {
                logArea.deleteText(0, cut);
            } else {
                logArea.clear();
            }
        }
        logArea.appendText(chunk);
        logArea.setScrollTop(Double.MAX_VALUE);
        logArea.positionCaret(logArea.getLength());
    }

    private void fatalError(String message, Exception e) {
        System.err.println(message + ": " + e.getMessage());
    }

    private void shutdown() {
        if (shutdownDone) {
            return;
        }
        shutdownDone = true;
        try {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
            }
            if (watcher != null) {
                watcher.stop();
            }
            if (discovery != null) {
                discovery.stop();
            }
            if (fileServer != null) {
                fileServer.stop();
            }
            syncExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void stop() {
        shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
