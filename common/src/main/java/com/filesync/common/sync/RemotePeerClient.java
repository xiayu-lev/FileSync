package com.filesync.common.sync;

import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.Manifest;

import java.io.File;
import java.io.IOException;

/**
 * 对端设备访问抽象。桌面端与 Android 端分别实现
 * （JDK HttpClient / OkHttp），同步引擎只依赖该接口。
 */
public interface RemotePeerClient {

    DeviceInfo getPeerInfo();

    Manifest fetchManifest() throws IOException;

    /** 下载文件到本地目标路径（实现内部先写 .part 临时文件再原子移动） */
    void downloadFile(String relPath, File localTarget, long expectedMtime,
                      ProgressCallback callback) throws IOException;

    /** 上传本地文件到对端 */
    void uploadFile(File localFile, String relPath, ProgressCallback callback) throws IOException;

    /** 在对端创建目录 */
    void makeDirectory(String relPath) throws IOException;

    /** 通知对端删除文件或目录，并由对端自行记录墓碑 */
    void deleteFile(String relPath) throws IOException;

    void close();
}
