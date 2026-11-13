package com.filesync.android.sync;

import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import com.filesync.common.protocol.SyncConstants;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 递归 FileObserver：为每个子目录维护独立观察者，
 * 新建目录自动纳入，事件经 500ms 去抖后回调。
 */
public class RecursiveFileObserver {

    private static final int WATCH_MASK = FileObserver.CREATE
            | FileObserver.MODIFY
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO;

    private static final long DEBOUNCE_MS = 500L;

    private final File root;
    private final Map<String, SingleDirObserver> observers = new ConcurrentHashMap<>();
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> pending = new HashMap<>();

    private FileEventListener listener;
    private volatile boolean watching;

    public RecursiveFileObserver(File root) {
        this.root = root;
    }

    public void setListener(FileEventListener listener) {
        this.listener = listener;
    }

    public synchronized void startWatching() {
        if (watching) {
            return;
        }
        watching = true;
        addDirectory(root);
    }

    public synchronized void stopWatching() {
        watching = false;
        for (SingleDirObserver observer : observers.values()) {
            observer.stopWatching();
        }
        observers.clear();
        debounceHandler.removeCallbacksAndMessages(null);
        pending.clear();
    }

    private void addDirectory(File dir) {
        if (!watching || !dir.isDirectory()) {
            return;
        }
        String key;
        try {
            key = dir.getCanonicalPath();
        } catch (Exception e) {
            key = dir.getAbsolutePath();
        }
        if (observers.containsKey(key)) {
            return;
        }
        SingleDirObserver observer = new SingleDirObserver(dir);
        observers.put(key, observer);
        observer.startWatching();

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !isInternal(child)) {
                    addDirectory(child);
                }
            }
        }
    }

    private void removeDirectory(File dir) {
        String key;
        try {
            key = dir.getCanonicalPath();
        } catch (Exception e) {
            key = dir.getAbsolutePath();
        }
        SingleDirObserver observer = observers.remove(key);
        if (observer != null) {
            observer.stopWatching();
        }
        // 移除所有子目录观察者
        String prefix = key + File.separator;
        for (String k : new java.util.ArrayList<>(observers.keySet())) {
            if (k.startsWith(prefix)) {
                SingleDirObserver o = observers.remove(k);
                if (o != null) {
                    o.stopWatching();
                }
            }
        }
    }

    private void handleEvent(File dir, int event, String name) {
        if (name == null) {
            return;
        }
        File child = new File(dir, name);
        if (isInternal(child)) {
            return;
        }
        String relPath = relative(child);

        // 传输中间文件（.part）与自身写入都不属于用户操作，直接忽略
        if (relPath.contains(SyncConstants.TEMP_SUFFIX)) {
            return;
        }
        if (WriteGuard.isGuarded(relPath)) {
            return;
        }

        if ((event & (FileObserver.CREATE | FileObserver.MOVED_TO)) != 0) {
            if (child.isDirectory()) {
                addDirectory(child);
            }
            scheduleDebounce(relPath);
        } else if ((event & FileObserver.MODIFY) != 0) {
            scheduleDebounce(relPath);
        } else if ((event & (FileObserver.DELETE | FileObserver.MOVED_FROM)) != 0) {
            removeDirectory(child);
            fireDeleted(relPath);
            scheduleDebounce(relPath);
        }
    }

    private void scheduleDebounce(String relPath) {
        Runnable old = pending.remove(relPath);
        if (old != null) {
            debounceHandler.removeCallbacks(old);
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                pending.remove(relPath);
                fireChanged(relPath);
            }
        };
        pending.put(relPath, task);
        debounceHandler.postDelayed(task, DEBOUNCE_MS);
    }

    private void fireChanged(String relPath) {
        FileEventListener l = listener;
        if (l != null) {
            l.onChanged(relPath);
        }
    }

    private void fireDeleted(String relPath) {
        FileEventListener l = listener;
        if (l != null) {
            l.onDeleted(relPath);
        }
    }

    private String relative(File child) {
        String base = root.getAbsolutePath();
        String p = child.getAbsolutePath();
        if (p.startsWith(base)) {
            p = p.substring(base.length());
        }
        return p.replace('\\', '/').replaceAll("^/", "");
    }

    private boolean isInternal(File file) {
        String rel = relative(file);
        return rel.equals(".filesync") || rel.startsWith(".filesync/");
    }

    /**
     * 单个目录的观察者。
     */
    private final class SingleDirObserver extends FileObserver {

        private final File dir;

        @SuppressWarnings("deprecation")
        SingleDirObserver(File dir) {
            super(dir.getAbsolutePath(), WATCH_MASK);
            this.dir = dir;
        }

        @Override
        public void onEvent(int event, String name) {
            handleEvent(dir, event, name);
        }
    }
}
