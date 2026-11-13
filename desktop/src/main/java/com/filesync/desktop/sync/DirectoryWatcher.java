package com.filesync.desktop.sync;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 WatchService 的递归目录监听。
 * 新建子目录会自动纳入监听；高频写入事件经 500ms 去抖后合并回调。
 */
public class DirectoryWatcher {

    private static final long DEBOUNCE_MS = 500L;

    private final Map<WatchKey, Path> keyPathMap = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    private WatchService watchService;
    private ScheduledExecutorService debouncer;
    private Thread worker;
    private volatile boolean running;
    private Path root;
    private DirectoryWatchListener listener;

    public void setListener(DirectoryWatchListener listener) {
        this.listener = listener;
    }

    public void start(Path rootPath) throws IOException {
        this.root = rootPath;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fs-watch-debounce");
            t.setDaemon(true);
            return t;
        });

        registerRecursive(rootPath);

        running = true;
        worker = new Thread(this::eventLoop, "fs-watcher");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        if (debouncer != null) {
            debouncer.shutdownNow();
        }
        if (worker != null) {
            worker.interrupt();
        }
        keyPathMap.clear();
        pending.clear();
    }

    private void registerRecursive(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyPathMap.put(key, dir);
    }

    private void eventLoop() {
        while (running) {
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            }

            Path dir = keyPathMap.get(key);
            if (dir != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    handleEvent(event, dir);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                keyPathMap.remove(key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEvent(WatchEvent<?> event, Path dir) {
        WatchEvent.Kind<?> kind = event.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            return;
        }
        Path name = ((WatchEvent<Path>) event).context();
        Path child = dir.resolve(name);
        String rel;
        try {
            rel = root.relativize(child).toString().replace('\\', '/');
        } catch (Exception e) {
            return;
        }
        if (rel.startsWith(".filesync") || rel.equals(".filesync")) {
            return;
        }

        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    registerRecursive(child);
                } catch (IOException e) {
                    System.err.println("[watcher] 新目录监听失败: " + e.getMessage());
                }
            }
            scheduleDebounce(rel);
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            scheduleDebounce(rel);
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            fireDeleted(rel);
            scheduleDebounce(rel);
        }
    }

    private void scheduleDebounce(String rel) {
        ScheduledFuture<?> old = pending.remove(rel);
        if (old != null) {
            old.cancel(false);
        }
        ScheduledFuture<?> future = debouncer.schedule(() -> {
            pending.remove(rel);
            fireChanged(rel);
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pending.put(rel, future);
    }

    private void fireChanged(String rel) {
        DirectoryWatchListener l = listener;
        if (l != null) {
            try {
                l.onChanged(rel);
            } catch (Exception e) {
                System.err.println("[watcher] 回调异常: " + e.getMessage());
            }
        }
    }

    private void fireDeleted(String rel) {
        DirectoryWatchListener l = listener;
        if (l != null) {
            try {
                l.onDeleted(rel);
            } catch (Exception e) {
                System.err.println("[watcher] 回调异常: " + e.getMessage());
            }
        }
    }

    // 便于测试的静态入口
    public static Path resolve(String first, String... more) {
        return Paths.get(first, more);
    }
}
