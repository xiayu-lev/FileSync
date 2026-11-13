package com.filesync.desktop.sync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面端自身写入屏蔽器。
 *
 * <p>同步引擎接收文件时（先写 .part 再 rename、覆盖前先 delete），
 * DirectoryWatcher 会把这类操作当成「用户删除」，进而写入墓碑，
 * 下一次同步就会把自己刚收到的文件当作已删除而误删对端文件。
 * 引擎在动作开始前登记路径，监听器遇到受保护路径则直接忽略。</p>
 */
public final class WriteGuard {

    /** 保护窗口：覆盖 rename / delete / 重新创建的整个事件序列 */
    private static final long TTL_MS = 10_000L;

    private static final Map<String, Long> GUARDED = new ConcurrentHashMap<>();

    private WriteGuard() {
    }

    public static void guard(String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return;
        }
        GUARDED.put(relPath, System.currentTimeMillis() + TTL_MS);
    }

    public static boolean isGuarded(String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return false;
        }
        Long expireAt = GUARDED.get(relPath);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= System.currentTimeMillis()) {
            GUARDED.remove(relPath);
            return false;
        }
        return true;
    }
}
