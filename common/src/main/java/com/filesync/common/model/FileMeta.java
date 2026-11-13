package com.filesync.common.model;

/**
 * 单个文件/目录的元数据。path 为相对同步根目录的路径，统一使用 '/' 分隔符。
 */
public class FileMeta {

    private String path;
    private long size;
    private long lastModified;
    private boolean directory;
    private String hash;

    public FileMeta() {
    }

    public FileMeta(String path, long size, long lastModified, boolean directory) {
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
        this.directory = directory;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
