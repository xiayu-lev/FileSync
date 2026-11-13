package com.filesync.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步根目录的完整清单：全部文件元数据 + 删除墓碑。
 */
public class Manifest {

    private String deviceId;
    private long generatedAt;
    private List<FileMeta> files = new ArrayList<>();
    private List<Tombstone> tombstones = new ArrayList<>();

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(long generatedAt) {
        this.generatedAt = generatedAt;
    }

    public List<FileMeta> getFiles() {
        return files;
    }

    public void setFiles(List<FileMeta> files) {
        this.files = files;
    }

    public List<Tombstone> getTombstones() {
        return tombstones;
    }

    public void setTombstones(List<Tombstone> tombstones) {
        this.tombstones = tombstones;
    }
}
