package com.filesync.common.sync;

/**
 * 传输进度回调。
 */
public interface ProgressCallback {

    void onProgress(String relPath, long transferred, long total);
}
