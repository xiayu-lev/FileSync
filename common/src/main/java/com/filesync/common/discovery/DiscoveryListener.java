package com.filesync.common.discovery;

import com.filesync.common.model.DeviceInfo;

/**
 * 设备发现事件监听器。回调运行在发现引擎内部线程，UI 更新需自行切线程。
 */
public interface DiscoveryListener {

    void onDeviceFound(DeviceInfo device);

    void onDeviceUpdated(DeviceInfo device);

    void onDeviceLost(DeviceInfo device);
}
