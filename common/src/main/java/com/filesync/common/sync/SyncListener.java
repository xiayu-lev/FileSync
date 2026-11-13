package com.filesync.common.sync;

/**
 * 同步过程事件监听器。
 */
public interface SyncListener {

    void onStart();

    void onAction(String action, String relPath);

    void onProgress(String relPath, long transferred, long total);

    void onActionDone(String action, String relPath);

    void onComplete(int changedCount);

    void onError(String relPath, Exception error);
}
