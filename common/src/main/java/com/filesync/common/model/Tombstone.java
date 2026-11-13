package com.filesync.common.model;

/**
 * 删除墓碑：记录被删除文件的相对路径与删除时间，
 * 使双向同步能够区分“文件被删除”和“文件尚未同步”。
 */
public class Tombstone {

    private String path;
    private long deletedAt;

    public Tombstone() {
    }

    public Tombstone(String path, long deletedAt) {
        this.path = path;
        this.deletedAt = deletedAt;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
