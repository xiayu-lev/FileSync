package com.filesync.android.net;

import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.Manifest;
import com.filesync.common.protocol.ApiPaths;
import com.filesync.common.protocol.JsonCodec;
import com.filesync.common.protocol.SyncConstants;
import com.filesync.common.sync.ProgressCallback;
import com.filesync.common.sync.RemotePeerClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;

/**
 * Android 端对端访问实现（OkHttp）。
 * 下载支持 .part 断点续传，上传通过自定义 Sink 回传进度。
 */
public class AndroidPeerClient implements RemotePeerClient {

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private final DeviceInfo peer;
    private final OkHttpClient client;
    private final String baseUrl;

    public AndroidPeerClient(DeviceInfo peer, OkHttpClient client) {
        this.peer = peer;
        this.client = client;
        this.baseUrl = "http://" + peer.getIp() + ":" + peer.getHttpPort();
    }

    @Override
    public DeviceInfo getPeerInfo() {
        return peer;
    }

    @Override
    public Manifest fetchManifest() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + ApiPaths.MANIFEST)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("清单请求失败，HTTP " + response.code());
            }
            return JsonCodec.fromJson(response.body().string(), Manifest.class);
        }
    }

    @Override
    public void downloadFile(String relPath, File localTarget, long expectedMtime,
                             ProgressCallback callback) throws IOException {
        File part = new File(localTarget.getParentFile(),
                localTarget.getName() + SyncConstants.TEMP_SUFFIX);
        long already = part.exists() ? part.length() : 0L;

        Request.Builder builder = new Request.Builder().url(fileUrl(relPath));
        if (already > 0) {
            builder.addHeader("Range", "bytes=" + already + "-");
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            int code = response.code();
            boolean append;
            long baseBytes;
            long total;
            if (code == 206) {
                append = true;
                baseBytes = already;
                total = parseTotal(response.header("Content-Range"));
            } else if (code == 200) {
                append = false;
                baseBytes = 0L;
                total = response.body().contentLength();
            } else {
                throw new IOException("下载失败，HTTP " + code);
            }

            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(part, append)) {
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
        }

        if (localTarget.exists() && !localTarget.delete()) {
            throw new IOException("无法覆盖目标文件: " + localTarget);
        }
        if (!part.renameTo(localTarget)) {
            throw new IOException("临时文件移动失败: " + part);
        }
        localTarget.setLastModified(expectedMtime);
    }

    @Override
    public void uploadFile(File localFile, String relPath, ProgressCallback callback)
            throws IOException {
        RequestBody raw = RequestBody.create(localFile, OCTET);
        RequestBody body = new ProgressRequestBody(raw, relPath, localFile.length(), callback);

        String url = baseUrl + ApiPaths.FILE + "?" + ApiPaths.PARAM_PATH + "="
                + encode(relPath) + "&" + ApiPaths.PARAM_MTIME + "=" + localFile.lastModified();
        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .build();
        executeEmpty(request);
    }

    @Override
    public void makeDirectory(String relPath) throws IOException {
        String url = baseUrl + ApiPaths.FILE + "?" + ApiPaths.PARAM_PATH + "="
                + encode(relPath) + "&dir=true";
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(new byte[0], null))
                .build();
        executeEmpty(request);
    }

    @Override
    public void deleteFile(String relPath) throws IOException {
        Request request = new Request.Builder()
                .url(fileUrl(relPath))
                .delete()
                .build();
        executeEmpty(request);
    }

    @Override
    public void close() {
        // OkHttpClient 由上层统一管理，这里不释放连接池
    }

    private void executeEmpty(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败，HTTP " + response.code());
            }
        }
    }

    private String fileUrl(String relPath) {
        return baseUrl + ApiPaths.FILE + "?" + ApiPaths.PARAM_PATH + "=" + encode(relPath);
    }

    private static long parseTotal(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
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
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 带进度回调的请求体包装。
     */
    private static final class ProgressRequestBody extends RequestBody {

        private final RequestBody delegate;
        private final String relPath;
        private final long expectedTotal;
        private final ProgressCallback callback;

        ProgressRequestBody(RequestBody delegate, String relPath,
                            long expectedTotal, ProgressCallback callback) {
            this.delegate = delegate;
            this.relPath = relPath;
            this.expectedTotal = expectedTotal;
            this.callback = callback;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            BufferedSink counting = Okio.buffer(new ForwardingSink(sink) {
                private long total = 0L;

                @Override
                public void write(Buffer source, long byteCount) throws IOException {
                    super.write(source, byteCount);
                    total += byteCount;
                    if (callback != null) {
                        callback.onProgress(relPath, total, expectedTotal);
                    }
                }
            });
            delegate.writeTo(counting);
            counting.flush();
        }
    }
}
