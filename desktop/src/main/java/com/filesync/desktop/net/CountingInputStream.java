package com.filesync.desktop.net;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统计读取字节数的输入流，用于上传进度回调。
 */
public class CountingInputStream extends FilterInputStream {

    public interface CountListener {
        void onBytes(long totalRead);
    }

    private final AtomicLong counter = new AtomicLong();
    private final CountListener listener;

    public CountingInputStream(InputStream in, CountListener listener) {
        super(in);
        this.listener = listener;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            notifyBytes(counter.incrementAndGet());
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n != -1) {
            notifyBytes(counter.addAndGet(n));
        }
        return n;
    }

    private void notifyBytes(long total) {
        if (listener != null) {
            listener.onBytes(total);
        }
    }
}
