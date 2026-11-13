package com.filesync.common.sync;

import com.filesync.common.model.Tombstone;
import com.filesync.common.protocol.JsonCodec;
import com.filesync.common.protocol.SyncConstants;
import com.filesync.common.util.PathUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 墓碑（删除记录）存储，原子写入 tombstones.json。
 */
public class TombstoneStore {

    /** 墓碑保留时长：30 天 */
    private static final long RETAIN_MS = 30L * 24 * 60 * 60 * 1000;

    private final File storeFile;
    private final List<Tombstone> tombstones = new ArrayList<>();

    /**
     * @param stateDir 状态目录（位于应用私有目录，不在同步根内），tombstones.json 就放在这里
     */
    public TombstoneStore(File stateDir) {
        this.storeFile = new File(stateDir, SyncConstants.TOMBSTONE_FILE);
    }

    public synchronized void load() throws IOException {
        tombstones.clear();
        if (!storeFile.exists()) {
            return;
        }
        String json = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
        Tombstone[] arr = JsonCodec.fromJson(json, Tombstone[].class);
        if (arr != null) {
            for (Tombstone t : arr) {
                tombstones.add(t);
            }
        }
    }

    public synchronized void add(String path) throws IOException {
        remove(path);
        // 内部状态与传输中间文件不应产生墓碑，否则会污染后续同步判定
        if (path == null || path.isEmpty()
                || PathUtils.isInternal(path)
                || path.contains(SyncConstants.TEMP_SUFFIX)) {
            return;
        }
        tombstones.add(new Tombstone(path, System.currentTimeMillis()));
        pruneExpired();
        save();
    }

    public synchronized void remove(String path) {
        tombstones.removeIf(t -> t.getPath().equals(path));
    }

    public synchronized Tombstone find(String path) {
        for (Tombstone t : tombstones) {
            if (t.getPath().equals(path)) {
                return t;
            }
        }
        return null;
    }

    public synchronized List<Tombstone> all() {
        return new ArrayList<>(tombstones);
    }

    public synchronized void pruneExpired() {
        long cutoff = System.currentTimeMillis() - RETAIN_MS;
        tombstones.removeIf(t -> t.getDeletedAt() < cutoff);
    }

    public synchronized void save() throws IOException {
        File parent = storeFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建内部状态目录: " + parent);
        }
        File tmp = new File(parent, SyncConstants.TOMBSTONE_FILE + ".tmp");
        String json = JsonCodec.toJson(tombstones);
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), storeFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
