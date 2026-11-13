package com.filesync.common.protocol;

/**
 * 同步过程使用的内部文件与状态常量。
 */
public final class SyncConstants {

    /** 同步根目录下存放内部状态的隐藏目录 */
    public static final String INTERNAL_DIR = ".filesync";

    /** 墓碑记录文件名 */
    public static final String TOMBSTONE_FILE = "tombstones.json";

    /** 传输中的临时文件后缀 */
    public static final String TEMP_SUFFIX = ".part";

    /** 冲突副本的文件名分隔片段 */
    public static final String CONFLICT_MARKER = ".conflict-";

    private SyncConstants() {
    }
}
