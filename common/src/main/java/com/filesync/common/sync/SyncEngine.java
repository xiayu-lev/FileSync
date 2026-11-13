package com.filesync.common.sync;

import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.FileMeta;
import com.filesync.common.model.Manifest;
import com.filesync.common.model.Tombstone;
import com.filesync.common.protocol.SyncConstants;
import com.filesync.common.util.FileWalker;
import com.filesync.common.util.PathUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 双向同步引擎，两端共用。
 *
 * 策略：
 * - 仅一侧存在的文件：向另一侧传输；
 * - 两侧都存在且元数据不同：按最后修改时间 LWW（last-write-wins）；
 * - 修改时间几乎相同但大小不同：保留双方，生成 .conflict-设备名 副本；
 * - 删除通过墓碑传播，避免把“已删除”误判为“待同步”。
 */
public class SyncEngine {

    public static final String ACTION_DOWNLOAD = "DOWNLOAD";
    public static final String ACTION_UPLOAD = "UPLOAD";
    public static final String ACTION_DELETE_LOCAL = "DELETE_LOCAL";
    public static final String ACTION_DELETE_REMOTE = "DELETE_REMOTE";
    public static final String ACTION_CONFLICT = "CONFLICT";
    public static final String ACTION_MKDIR = "MKDIR";

    /** 两端设备时钟偏差容忍窗口 */
    private static final long MTIME_SKEW_MS = 2000L;

    private final File syncRoot;
    private final DeviceInfo self;
    private final TombstoneStore tombstoneStore;

    private volatile RemotePeerClient client;
    private volatile SyncListener listener;
    private volatile boolean cancelled;

    public SyncEngine(File syncRoot, DeviceInfo self) throws IOException {
        this.syncRoot = syncRoot;
        this.self = self;
        this.tombstoneStore = new TombstoneStore(syncRoot);
        this.tombstoneStore.load();
    }

    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    public TombstoneStore tombstoneStore() {
        return tombstoneStore;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 执行一次完整双向同步，返回成功变更的条目数。
     */
    public int runOnce(RemotePeerClient peer) throws IOException {
        this.client = peer;
        this.cancelled = false;
        int changed = 0;
        SyncListener l = listener;
        if (l != null) {
            l.onStart();
        }

        try {
            Manifest remoteManifest = peer.fetchManifest();
            Map<String, FileMeta> remoteMap = indexFiles(remoteManifest.getFiles());
            Map<String, FileMeta> localMap = indexFiles(FileWalker.walk(syncRoot));
            Map<String, Tombstone> remoteTomb = indexTombstones(remoteManifest.getTombstones());

            // 1. 对端墓碑：删除本地仍保留、且删除时间之后未再修改的文件
            //    对端清单里同时还能看到这个文件时，说明墓碑已过期（文件被重建），不能删本地
            for (Tombstone t : remoteManifest.getTombstones()) {
                if (cancelled) {
                    break;
                }
                FileMeta local = localMap.get(t.getPath());
                if (local != null && !remoteMap.containsKey(t.getPath())
                        && local.getLastModified() <= t.getDeletedAt()) {
                    if (deleteLocal(t.getPath())) {
                        changed++;
                    }
                }
            }

            // 2. 本地墓碑：通知对端删除其仍保留的文件
            //    本地文件又存在（重建/复制回来）时墓碑作废，否则会反复误删对端文件
            for (Tombstone t : tombstoneStore.all()) {
                if (cancelled) {
                    break;
                }
                if (localMap.get(t.getPath()) != null) {
                    tombstoneStore.remove(t.getPath());
                    continue;
                }
                FileMeta remote = remoteMap.get(t.getPath());
                if (remote != null && !remoteTomb.containsKey(t.getPath())
                        && remote.getLastModified() <= t.getDeletedAt()) {
                    try {
                        fireAction(ACTION_DELETE_REMOTE, t.getPath());
                        peer.deleteFile(t.getPath());
                        fireActionDone(ACTION_DELETE_REMOTE, t.getPath());
                        changed++;
                    } catch (Exception e) {
                        // 对端文件已不存在（404）说明删除目标已达成，不算失败
                        if (String.valueOf(e.getMessage()).contains("404")) {
                            fireActionDone(ACTION_DELETE_REMOTE, t.getPath());
                            changed++;
                        } else {
                            fireError(t.getPath(), e);
                        }
                    }
                }
            }

            // 3. 逐路径对比
            Set<String> allPaths = new HashSet<>();
            allPaths.addAll(localMap.keySet());
            allPaths.addAll(remoteMap.keySet());
            List<String> ordered = new ArrayList<>(allPaths);
            Collections.sort(ordered);

            for (String path : ordered) {
                if (cancelled) {
                    break;
                }
                if (PathUtils.isInternal(path)) {
                    continue;
                }
                if (tombstoneStore.find(path) != null || remoteTomb.containsKey(path)) {
                    FileMeta localNow = localMap.get(path);
                    Tombstone localTomb = tombstoneStore.find(path);
                    if (localTomb != null) {
                        // 本地文件比墓碑新：说明删除后又被重建，墓碑作废，正常同步
                        if (localNow != null
                                && localNow.getLastModified() > localTomb.getDeletedAt()) {
                            tombstoneStore.remove(path);
                        } else {
                            continue;
                        }
                    }
                    Tombstone remoteTombstone = remoteTomb.get(path);
                    if (remoteTombstone != null) {
                        boolean revived = localNow != null
                                && localNow.getLastModified() > remoteTombstone.getDeletedAt();
                        if (!revived) {
                            continue;
                        }
                    }
                }
                FileMeta local = localMap.get(path);
                FileMeta remote = remoteMap.get(path);
                try {
                    if (local == null && remote != null) {
                        receive(path, remote);
                        changed++;
                    } else if (local != null && remote == null) {
                        send(path, local);
                        changed++;
                    } else if (local != null && metaDiffer(local, remote)) {
                        if (resolveConflict(path, local, remote)) {
                            changed++;
                        }
                    }
                } catch (Exception e) {
                    fireError(path, e);
                }
            }

            tombstoneStore.pruneExpired();
            tombstoneStore.save();
            return changed;
        } finally {
            if (l != null) {
                l.onComplete(changed);
            }
        }
    }

    /** 本地文件被用户/监听层删除时记录墓碑 */
    public void recordLocalDelete(String relPath) throws IOException {
        tombstoneStore.add(relPath);
    }

    private void receive(String path, FileMeta remote) throws IOException {
        File target = PathUtils.resolveSafe(syncRoot, path);
        if (remote.isDirectory()) {
            fireAction(ACTION_MKDIR, path);
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("无法创建目录: " + path);
            }
            fireActionDone(ACTION_MKDIR, path);
            return;
        }
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建父目录: " + parent);
        }
        fireAction(ACTION_DOWNLOAD, path);
        client.downloadFile(path, target, remote.getLastModified(), progressFor(path));
        fireActionDone(ACTION_DOWNLOAD, path);
    }

    private void send(String path, FileMeta local) throws IOException {
        File source = PathUtils.resolveSafe(syncRoot, path);
        if (local.isDirectory()) {
            fireAction(ACTION_MKDIR, path);
            client.makeDirectory(path);
            fireActionDone(ACTION_MKDIR, path);
            return;
        }
        fireAction(ACTION_UPLOAD, path);
        client.uploadFile(source, path, progressFor(path));
        fireActionDone(ACTION_UPLOAD, path);
    }

    private boolean resolveConflict(String path, FileMeta local, FileMeta remote) throws IOException {
        if (local.isDirectory() != remote.isDirectory()) {
            saveConflictCopy(path, remote);
            return true;
        }
        long lm = local.getLastModified();
        long rm = remote.getLastModified();
        if (Math.abs(lm - rm) <= MTIME_SKEW_MS) {
            if (local.getSize() == remote.getSize()) {
                return false;
            }
            saveConflictCopy(path, remote);
            return true;
        }
        if (lm > rm) {
            send(path, local);
        } else {
            receive(path, remote);
        }
        return true;
    }

    /** 保留本地文件，同时把对端版本下载为冲突副本 */
    private void saveConflictCopy(String path, FileMeta remote) throws IOException {
        String peerName = safeName(client.getPeerInfo().getDeviceName());
        String conflictPath = buildConflictPath(path, peerName);
        File target = PathUtils.resolveSafe(syncRoot, conflictPath);
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建父目录: " + parent);
        }
        fireAction(ACTION_CONFLICT, path);
        client.downloadFile(path, target, remote.getLastModified(), progressFor(path));
        fireActionDone(ACTION_CONFLICT, path);
    }

    private boolean deleteLocal(String path) {
        try {
            File target = PathUtils.resolveSafe(syncRoot, path);
            fireAction(ACTION_DELETE_LOCAL, path);
            if (target.isDirectory()) {
                deleteRecursively(target);
            } else if (!target.delete()) {
                throw new IOException("删除失败: " + path);
            }
            tombstoneStore.add(path);
            fireActionDone(ACTION_DELETE_LOCAL, path);
            return true;
        } catch (Exception e) {
            fireError(path, e);
            return false;
        }
    }

    private static boolean metaDiffer(FileMeta a, FileMeta b) {
        if (a.isDirectory() != b.isDirectory()) {
            return true;
        }
        if (a.isDirectory()) {
            return false;
        }
        if (a.getSize() != b.getSize()) {
            return true;
        }
        return Math.abs(a.getLastModified() - b.getLastModified()) > MTIME_SKEW_MS;
    }

    private ProgressCallback progressFor(String path) {
        return (relPath, transferred, total) -> {
            SyncListener l = listener;
            if (l != null) {
                l.onProgress(path, transferred, total);
            }
        };
    }

    private void fireAction(String action, String path) {
        SyncListener l = listener;
        if (l != null) {
            l.onAction(action, path);
        }
    }

    private void fireActionDone(String action, String path) {
        SyncListener l = listener;
        if (l != null) {
            l.onActionDone(action, path);
        }
    }

    private void fireError(String path, Exception e) {
        SyncListener l = listener;
        if (l != null) {
            l.onError(path, e);
        } else {
            System.err.println("[sync] " + path + " 处理失败: " + e.getMessage());
        }
    }

    private static String buildConflictPath(String path, String peerName) {
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return dir + name + SyncConstants.CONFLICT_MARKER + peerName;
        }
        return dir + name.substring(0, dot) + SyncConstants.CONFLICT_MARKER
                + peerName + name.substring(dot);
    }

    private static String safeName(String name) {
        String n = name == null ? "peer" : name;
        StringBuilder sb = new StringBuilder();
        for (char c : n.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "peer" : sb.toString();
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private static Map<String, FileMeta> indexFiles(List<FileMeta> files) {
        Map<String, FileMeta> map = new LinkedHashMap<>();
        for (FileMeta f : files) {
            map.put(f.getPath(), f);
        }
        return map;
    }

    private static Map<String, Tombstone> indexTombstones(List<Tombstone> list) {
        Map<String, Tombstone> map = new HashMap<>();
        for (Tombstone t : list) {
            map.put(t.getPath(), t);
        }
        return map;
    }

    public DeviceInfo getSelf() {
        return self;
    }
}
