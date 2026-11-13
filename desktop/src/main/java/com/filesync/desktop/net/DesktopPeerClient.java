package com.filesync.desktop.net;

import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.Manifest;
import com.filesync.common.protocol.ApiPaths;
import com.filesync.common.protocol.JsonCodec;
import com.filesync.common.protocol.SyncConstants;
import com.filesync.common.sync.ProgressCallback;
import com.filesync.common.sync.RemotePeerClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 桌面端对端访问实现（JDK 原生 HttpClient）。
 * 下载支持断点续传：中断后保留 .part 文件，下次带 Range 续传。
 */
public class DesktopPeerClient implements RemotePeerClient {

    private final DeviceInfo peer;
    private final String baseUrl;
    private final HttpClient http;

    public DesktopPeerClient(DeviceInfo peer) {
        this.peer = peer;
        this.baseUrl = "http://" + peer.getIp() + ":" + peer.getHttpPort();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    @Override
    public DeviceInfo getPeerInfo() {
        return peer;
    }

    @Override
    public Manifest fetchManifest() throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + ApiPaths.MANIFEST))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return sendForJson(req, Manifest.class);
    }

    @Override
    public void downloadFile(String relPath, File localTarget, long expectedMtime,
                             ProgressCallback callback) throws IOException {
        File part = new File(localTarget.getParentFile(),
                localTarget.getName() + SyncConstants.TEMP_SUFFIX);
        long already = part.exists() ? part.length() : 0L;

        HttpRequest.Builder builder = HttpRequest.newBuilder(fileUri(relPath))
                .timeout(Duration.ofMinutes(30));
        if (already > 0) {
            builder.header("Range", "bytes=" + already + "-");
        }
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }

        int code = resp.statusCode();
        boolean append;
        long baseBytes;
        long total;
        if (code == 206) {
            append = true;
            baseBytes = already;
            total = parseTotalFromContentRange(
                    resp.headers().firstValue("Content-Range").orElse(""));
        } else if (code == 200) {
            append = false;
            baseBytes = 0L;
            total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        } else {
            resp.body().close();
            throw new IOException("下载失败，HTTP " + code);
        }

        try (InputStream in = resp.body();
             FileOutputStream out = new FileOutputStream(part, append)) {
            byte[] buffer = new byte[64 * 1024];
            long transferred = baseBytes;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                transferred += read;
                if (callback != null) {
                    callback.onProgress(relPath, transferred, total);
                }
            }
        }

        Files.move(part.toPath(), localTarget.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        localTarget.setLastModified(expectedMtime);
    }

    @Override
    public void uploadFile(File localFile, String relPath, ProgressCallback callback)
            throws IOException {
        long length = localFile.length();
        HttpRequest.BodyPublisher stream = HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                InputStream raw = new FileInputStream(localFile);
                return new CountingInputStream(raw,
                        total -> {
                            if (callback != null) {
                                callback.onProgress(relPath, total, length);
                            }
                        });
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }
        });
        // 显式声明长度，避免使用 chunked 编码（部分简易 HTTP 服务器不支持）
        HttpRequest.BodyPublisher publisher =
                HttpRequest.BodyPublishers.fromPublisher(stream, length);

        String url = baseUrl + ApiPaths.FILE + "?" + ApiPaths.PARAM_PATH + "="
                + encode(relPath) + "&" + ApiPaths.PARAM_MTIME + "=" + localFile.lastModified();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/octet-stream")
                .PUT(publisher)
                .build();
        sendEmpty(req);
    }

    @Override
    public void makeDirectory(String relPath) throws IOException {
        String url = baseUrl + ApiPaths.FILE + "?" + ApiPaths.PARAM_PATH + "="
                + encode(relPath) + "&dir=true";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        sendEmpty(req);
    }

    @Override
    public void deleteFile(String relPath) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(fileUri(relPath))
                .DELETE()
                .build();
        sendEmpty(req);
    }

    @Override
    public void close() {
        // JDK HttpClient 无需显式关闭
    }

    private URI fileUri(String relPath) {
        return URI.create(baseUrl + ApiPaths.FILE + "?" + ApiPaths.PARAM_PATH
                + "=" + encode(relPath));
    }

    private void sendEmpty(HttpRequest req) throws IOException {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("请求失败，HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    private <T> T sendForJson(HttpRequest req, Class<T> clazz) throws IOException {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("请求失败，HTTP " + resp.statusCode());
        }
        return JsonCodec.fromJson(resp.body(), clazz);
    }

    private static long parseTotalFromContentRange(String contentRange) {
        // 形如：bytes 1000-1999/5000
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
