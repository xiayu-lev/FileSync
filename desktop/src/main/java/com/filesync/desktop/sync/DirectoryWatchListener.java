package com.filesync.desktop.sync;

/**
 * 目录变化事件。
 */
public interface DirectoryWatchListener {

    /** 创建/修改（已去抖） */
    void onChanged(String relPath);

    /** 删除，上层需据此记录墓碑 */
    void onDeleted(String relPath);
}
