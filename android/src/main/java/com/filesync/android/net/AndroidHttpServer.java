package com.filesync.android.net;

import com.filesync.android.sync.WriteGuard;
import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.Manifest;
import com.filesync.common.protocol.ApiPaths;
import com.filesync.common.protocol.JsonCodec;
import com.filesync.common.protocol.SyncConstants;
import com.filesync.common.sync.TombstoneStore;
import com.filesync.common.util.FileWalker;
import com.filesync.common.util.PathUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Android 端 HTTP 文件服务器（NanoHTTPD），与桌面端实现同一套 API 契约。
 */
public class AndroidHttpServer extends NanoHTTPD {

    private static final String MIME_OCTET = "application/octet-stream";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    private final DeviceInfo self;
    private final TombstoneStore tombstoneStore;
    private volatile File syncRoot;

    public AndroidHttpServer(int port, DeviceInfo self, File syncRoot,
                             TombstoneStore tombstoneStore) {
        // 必须显式绑定 IPv4 通配地址：默认构造只监听 [::]，
        // 在 Android 上会拒绝来自局域网的 IPv4 连接，导致电脑端连不上。
        super("0.0.0.0", port);
        this.self = self;
        this.syncRoot = syncRoot;
        this.tombstoneStore = tombstoneStore;
    }

    public void setSyncRoot(File syncRoot) {
        this.syncRoot = syncRoot;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            Map<String, String> params = session.getParms();

            if (ApiPaths.PING.equals(uri)) {
                return json(Response.Status.OK, mapOf("status", "ok"));
            }
            if (ApiPaths.INFO.equals(uri)) {
                return json(Response.Status.OK, self);
            }
            if (ApiPaths.MANIFEST.equals(uri)) {
                return serveManifest();
            }
            if (ApiPaths.FILE.equals(uri)) {
                return serveFile(session, method, params);
            }
            return json(Response.Status.NOT_FOUND, mapOf("error", "未知路径"));
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR,
                    mapOf("error", e.getMessage() == null ? "内部错误" : e.getMessage()));
        }
    }

    private Response serveManifest() {
        Manifest manifest = new Manifest();
        manifest.setDeviceId(self.getDeviceId());
        manifest.setGeneratedAt(System.currentTimeMillis());
        manifest.setFiles(syncRoot == null ? java.util.Collections.emptyList()
                : FileWalker.walk(syncRoot));
        manifest.setTombstones(tombstoneStore == null
                ? java.util.Collections.emptyList() : tombstoneStore.all());
        return json(Response.Status.OK, manifest);
    }

    private Response serveFile(IHTTPSession session, Method method, Map<String, String> params)
            throws IOException {
        if (syncRoot == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf("error", "尚未绑定同步目录"));
        }
        String relPath = PathUtils.normalize(params.getOrDefault(ApiPaths.PARAM_PATH, ""));
        if (relPath.isEmpty() || PathUtils.isInternal(relPath)) {
            return json(Response.Status.BAD_REQUEST, mapOf("error", "非法路径"));
        }

        if (method == Method.GET) {
            return serveGet(relPath, session.getHeaders());
        }
        if (method == Method.PUT) {
            return servePut(relPath, params, session);
        }
        if (method == Method.DELETE) {
            return serveDelete(relPath);
        }
        return json(Response.Status.METHOD_NOT_ALLOWED, mapOf("error", "不支持的方法"));
    }

    private Response serveGet(String relPath, Map<String, String> headers) throws IOException {
        File file = PathUtils.resolveSafe(syncRoot, relPath);
        if (!file.exists() || file.isDirectory()) {
            return json(Response.Status.NOT_FOUND, mapOf("error", "文件不存在"));
        }

        long length = file.length();
        long start = 0L;
        long end = length - 1;
        Response.Status status = Response.Status.OK;

        String range = headers.get("range");
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
                Response r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_JSON, "");
                r.addHeader("Content-Range", "bytes */" + length);
                return r;
            }
            status = Response.Status.PARTIAL_CONTENT;
        }

        final RandomAccessFile raf = new RandomAccessFile(file, "r");
        raf.seek(start);
        final long remainingBytes = end - start + 1;
        InputStream in = new InputStream() {
            private long remaining = remainingBytes;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = raf.read();
                if (b != -1) {
                    remaining--;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = raf.read(b, off, (int) Math.min(len, remaining));
                if (n > 0) {
                    remaining -= n;
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                raf.close();
            }
        };

        Response response = newFixedLengthResponse(status, MIME_OCTET, in, remainingBytes);
        if (status == Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private Response servePut(String relPath, Map<String, String> params, IHTTPSession session)
            throws IOException {
        File target = PathUtils.resolveSafe(syncRoot, relPath);

        if ("true".equalsIgnoreCase(params.get("dir"))) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("无法创建目录: " + relPath);
            }
            return json(Response.Status.OK, mapOf("status", "ok"));
        }

        long mtime = params.containsKey(ApiPaths.PARAM_MTIME)
                ? Long.parseLong(params.get(ApiPaths.PARAM_MTIME))
                : System.currentTimeMillis();

        // 直接读取原始请求体：NanoHTTPD 的 parseBody 仅对 POST 有效，
        // PUT 会被静默丢弃，因此此处基于 Content-Length 手动落盘。
        String lenHeader = session.getHeaders().get("content-length");
        long contentLength;
        try {
            contentLength = lenHeader == null ? -1L : Long.parseLong(lenHeader.trim());
        } catch (NumberFormatException e) {
            return json(Response.Status.BAD_REQUEST, mapOf("error", "非法的 Content-Length"));
        }
        if (contentLength < 0) {
            return json(Response.Status.BAD_REQUEST, mapOf("error", "必须提供 Content-Length"));
        }

        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建父目录: " + parent);
        }
        // 登记写入保护，避免观察者把自己写入产生的 delete/rename 当成用户删除
        WriteGuard.guard(relPath);
        File part = new File(parent, target.getName() + SyncConstants.TEMP_SUFFIX
                + "-" + Thread.currentThread().getId());

        if (contentLength == 0) {
            if (!part.createNewFile() && !part.exists()) {
                throw new IOException("无法创建文件: " + part);
            }
        } else {
            // 注意：session.getInputStream() 就是 NanoHTTPD 的底层 socket 流，
            // 绝不能 close()，否则响应还没发出连接就被断开。
            InputStream in = session.getInputStream();
            FileOutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[64 * 1024];
                long remaining = contentLength;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n <= 0) {
                        throw new IOException("请求体提前结束，剩余 " + remaining + " 字节");
                    }
                    out.write(buf, 0, n);
                    remaining -= n;
                }
                out.flush();
            } finally {
                out.close();
            }
        }

        if (target.exists() && !target.delete()) {
            part.delete();
            throw new IOException("无法覆盖目标: " + target);
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("临时文件移动失败: " + part);
        }
        target.setLastModified(mtime);
        // 文件已重新写入，清掉可能残留的同名墓碑，否则下一次同步会把它当成已删除
        if (tombstoneStore != null && tombstoneStore.find(relPath) != null) {
            tombstoneStore.remove(relPath);
            try {
                tombstoneStore.save();
            } catch (IOException ignored) {
                // 墓碑清理失败不影响本次写入结果
            }
        }
        return json(Response.Status.OK, mapOf("status", "ok"));
    }

    private Response serveDelete(String relPath) throws IOException {
        File target = PathUtils.resolveSafe(syncRoot, relPath);
        WriteGuard.guard(relPath);
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
        return json(Response.Status.OK, mapOf("status", "ok"));
    }

    private Response json(Response.Status status, Object body) {
        return newFixedLengthResponse(status, MIME_JSON, JsonCodec.toJson(body));
    }

    private static Map<String, String> mapOf(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
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
