package com.filesync.common.protocol;

/**
 * 局域网 UDP 设备发现协议常量。
 */
public final class DiscoveryConstants {

    /** 设备发现固定 UDP 端口 */
    public static final int DISCOVERY_PORT = 19090;

    /** 协议标识，过滤无关广播包 */
    public static final String MAGIC = "FILESYNC_DISCOVERY_V1";

    /** 消息类型：周期性主动广播 */
    public static final String MSG_ANNOUNCE = "ANNOUNCE";

    /** 消息类型：收到 ANNOUNCE 后单播回应，加速互相发现 */
    public static final String MSG_REPLY = "REPLY";

    public static final int PROTOCOL_VERSION = 1;

    /** 广播间隔（毫秒） */
    public static final long ANNOUNCE_INTERVAL_MS = 3000L;

    /** 设备离线判定时间（毫秒） */
    public static final long DEVICE_EXPIRE_MS = 10000L;

    private DiscoveryConstants() {
    }
}
