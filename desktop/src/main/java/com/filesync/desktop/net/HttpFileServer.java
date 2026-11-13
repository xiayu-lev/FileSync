package com.filesync.desktop.net;

import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.Manifest;
import com.filesync.common.protocol.ApiPaths;
import com.filesync.common.protocol.JsonCodec;
import com.filesync.common.protocol.SyncConstants;
import com.filesync.common.sync.TombstoneStore;
import com.filesync.common.util.FileWalker;
import com.filesync.common.util.PathUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 桌面端 HTTP 文件服务器，实现双端统一 API 契约。
 * 支持 GET Range 断点下载、PUT 上传（原子落盘）、DELETE 墓碑删除。
 */
public class HttpFileServer {

    private final DeviceInfo self;

    private volatile File syncRoot;
    private volatile TombstoneStore tombstoneStore;
    private volatile java.util.function.Consumer<String> logListener;
    private HttpServer server;

    public HttpFileServer(DeviceInfo self, File syncRoot, TombstoneStore tombstoneStore) {
        this.self = self;
        this.syncRoot = syncRoot;
        this.tombstoneStore = tombstoneStore;
    }

    public void setSyncRoot(File syncRoot) {
        this.syncRoot = syncRoot;
    }

    public void setTombstoneStore(TombstoneStore tombstoneStore) {
        this.tombstoneStore = tombstoneStore;
    }

    /** 接收对端推送来的同步日志（对端正在同步时，本机同样实时显示） */
    public void setLogListener(java.util.function.Consumer<String> listener) {
        this.logListener = listener;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(self.getHttpPort()), 0);
        server.createContext(ApiPaths.INFO, this::handleInfo);
        server.createContext(ApiPaths.MANIFEST, this::handleManifest);
        server.createContext(ApiPaths.FILE, this::handleFile);
        server.createContext(ApiPaths.PING, this::handlePing);
        server.createContext(ApiPaths.LOG, this::handlePeerLog);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    public int getActualPort() {
        return server == null ? self.getHttpPort() : server.getAddress().getPort();
    }

    private void handlePing(HttpExchange ex) throws IOException {
        writeJson(ex, 200, Map.of("status", "ok"));
    }

    /** 接收对端推送的日志行并交给界面显示 */
    private void handlePeerLog(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            writeError(ex, 405, "日志通道仅支持 POST");
            return;
        }
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            com.filesync.common.model.PeerLogMessage msg =
                    JsonCodec.fromJson(body, com.filesync.common.model.PeerLogMessage.class);
            if (msg == null || msg.getText() == null) {
                writeError(ex, 400, "日志内容为空");
                return;
            }
            java.util.function.Consumer<String> listener = logListener;
            if (listener != null) {
                String from = msg.getFrom() == null ? "对端" : msg.getFrom();
                listener.accept("[来自 " + from + "] " + msg.getText());
            }
            writeJson(ex, 200, Map.of("status", "ok"));
        } catch (Exception e) {
            writeError(ex, 500, "日志接收失败: " + e.getMessage());
        }
    }

    private void handleInfo(HttpExchange ex) throws IOException {
        writeJson(ex, 200, self);
    }

    private void handleManifest(HttpExchange ex) throws IOException {
        File root = syncRoot;
        Manifest manifest = new Manifest();
        manifest.setDeviceId(self.getDeviceId());
        manifest.setGeneratedAt(System.currentTimeMillis());
        manifest.setFiles(root == null ? java.util.Collections.emptyList()
                : FileWalker.walk(root));
        manifest.setTombstones(tombstoneStore == null
                ? java.util.Collections.emptyList() : tombstoneStore.all());
        writeJson(ex, 200, manifest);
    }

    private void handleFile(HttpExchange ex) throws IOException {
        try {
            if (syncRoot == null) {
                writeError(ex, 503, "尚未绑定同步目录");
                return;
            }
            Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
            String relPath = PathUtils.normalize(params.getOrDefault(ApiPaths.PARAM_PATH, ""));
            if (relPath.isEmpty() || PathUtils.isInternal(relPath)) {
                writeError(ex, 400, "非法路径");
                return;
            }

            switch (ex.getRequestMethod()) {
                case "GET":
                    handleGet(ex, relPath);
                    break;
                case "PUT":
                    handlePut(ex, relPath, params);
                    break;
                case "DELETE":
                    handleDelete(ex, relPath);
                    break;
                default:
                    writeError(ex, 405, "不支持的方法");
            }
        } catch (IllegalArgumentException e) {
            writeError(ex, 400, e.getMessage());
        } catch (Exception e) {
            writeError(ex, 500, "服务器内部错误: " + e.getMessage());
        }
    }

    private void handleGet(HttpExchange ex, String relPath) throws IOException {
        File file = PathUtils.resolveSafe(syncRoot, relPath);
        if (!file.exists() || file.isDirectory()) {
            writeError(ex, 404, "文件不存在");
            return;
        }

        long length = file.length();
        long start = 0L;
        long end = length - 1;
        int status = 200;

        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String left = spec.substring(0, dash).trim();
                String right = spec.substring(dash + 1).trim();
                if (left.isEmpty()) {
                    long suffix = Long.parseLong(right);
                    start = Math.max(0L, length - suffix);
                } else {
                    start = Long.parseLong(left);
                    if (!right.isEmpty()) {
                        end = Long.parseLong(right);
                    }
                }
            }
            if (start > end || start >= length) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + length);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            status = 206;
            ex.getResponseHeaders().set("Content-Range",
                    "bytes " + start + "-" + end + "/" + length);
        }

        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.sendResponseHeaders(status, end - start + 1);

        byte[] buffer = new byte[64 * 1024];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             OutputStream os = ex.getResponseBody()) {
            raf.seek(start);
            long remaining = end - start + 1;
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, chunk);
                if (read == -1) {
                    break;
                }
                os.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void handlePut(HttpExchange ex, String relPath, Map<String, String> params)
            throws IOException {
        File target = PathUtils.resolveSafe(syncRoot, relPath);

        if ("true".equalsIgnoreCase(params.get("dir"))) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("无法创建目录: " + relPath);
            }
            writeJson(ex, 200, Map.of("status", "ok"));
            return;
        }

        long mtime = params.containsKey(ApiPaths.PARAM_MTIME)
                ? Long.parseLong(params.get(ApiPaths.PARAM_MTIME))
                : System.currentTimeMillis();

        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建父目录: " + parent);
        }
        File tmp = new File(parent, target.getName() + SyncConstants.TEMP_SUFFIX
                + "-" + Thread.currentThread().getId());

        try (InputStream in = ex.getRequestBody();
             OutputStream out = new FileOutputStream(tmp)) {
            in.transferTo(out);
        }
        Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        target.setLastModified(mtime);

        writeJson(ex, 200, Map.of("status", "ok"));
    }

    private void handleDelete(HttpExchange ex, String relPath) throws IOException {
        File target = PathUtils.resolveSafe(syncRoot, relPath);
        if (target.exists()) {
            if (target.isDirectory()) {
                deleteRecursively(target);
            } else if (!target.delete()) {
                throw new IOException("删除失败: " + relPath);
            }
        }
        if (tombstoneStore != null) {
            tombstoneStore.add(relPath);
        }
        writeJson(ex, 200, Map.of("status", "ok"));
    }
    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = JsonCodec.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void writeError(HttpExchange ex, int status, String message) throws IOException {
        writeJson(ex, status, Map.of("error", message));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
