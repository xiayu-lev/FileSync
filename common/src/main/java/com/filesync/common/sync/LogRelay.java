package com.filesync.common.sync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志转发线程池：跨端日志推送不能占用同步线程，也不能阻塞界面线程。
 * 单线程保证日志先后顺序与本地一致。
 */
public final class LogRelay {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fs-log-relay");
        t.setDaemon(true);
        return t;
    });

    private LogRelay() {
    }

    public static void post(Runnable task) {
        POOL.execute(task);
    }
}
