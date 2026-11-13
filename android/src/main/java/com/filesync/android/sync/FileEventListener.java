package com.filesync.android.sync;

/**
 * Android 文件变化事件。
 */
public interface FileEventListener {

    void onChanged(String relPath);

    void onDeleted(String relPath);
}
