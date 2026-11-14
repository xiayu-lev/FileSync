package com.filesync.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

/**
 * 桌面端持久化配置：设备 ID、设备名、上次同步目录。
 */
public class AppConfig {

    private static final String CONFIG_DIR_NAME = ".filesync-desktop";
    private static final String CONFIG_FILE_NAME = "config.properties";

    private final Path configDir;
    private final Properties props = new Properties();

    private String deviceId;
    private String deviceName;
    private String lastSyncDir;
    private boolean autoStart = true;

    public AppConfig() {
        this.configDir = Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME);
    }

    public void load() throws IOException {
        File file = configDir.resolve(CONFIG_FILE_NAME).toFile();
        if (file.exists()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                props.load(in);
            }
        }
        deviceId = props.getProperty("deviceId");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            props.setProperty("deviceId", deviceId);
        }
        deviceName = props.getProperty("deviceName", defaultDeviceName());
        lastSyncDir = props.getProperty("lastSyncDir", "");
        autoStart = Boolean.parseBoolean(props.getProperty("autoStart", "true"));
    }

    public void save() throws IOException {
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        props.setProperty("deviceId", deviceId);
        props.setProperty("deviceName", deviceName);
        props.setProperty("lastSyncDir", lastSyncDir == null ? "" : lastSyncDir);
        props.setProperty("autoStart", Boolean.toString(autoStart));
        try (OutputStream out = Files.newOutputStream(configDir.resolve(CONFIG_FILE_NAME))) {
            props.store(out, "FileSync desktop config");
        }
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getLastSyncDir() {
        return lastSyncDir;
    }

    public void setLastSyncDir(String lastSyncDir) {
        this.lastSyncDir = lastSyncDir;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    private static String defaultDeviceName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = System.getProperty("user.name", "computer");
        }
        return host + "-PC";
    }
}
