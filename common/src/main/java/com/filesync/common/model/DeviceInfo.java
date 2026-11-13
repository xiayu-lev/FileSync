package com.filesync.common.model;

/**
 * 设备信息：用于设备发现与 HTTP /api/info 接口。
 */
public class DeviceInfo {

    public static final String TYPE_ANDROID = "ANDROID";
    public static final String TYPE_DESKTOP = "DESKTOP";

    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String ip;
    private int httpPort;
    private int protocolVersion;

    public DeviceInfo() {
    }

    public DeviceInfo(String deviceId, String deviceName, String deviceType,
                      String ip, int httpPort, int protocolVersion) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.ip = ip;
        this.httpPort = httpPort;
        this.protocolVersion = protocolVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    @Override
    public String toString() {
        return deviceName + " (" + ip + ":" + httpPort + ")";
    }
}
