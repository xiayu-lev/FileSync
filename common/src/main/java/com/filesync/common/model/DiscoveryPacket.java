package com.filesync.common.model;

/**
 * UDP 发现报文。
 */
public class DiscoveryPacket {

    private String magic;
    private String messageType;
    private DeviceInfo device;

    public DiscoveryPacket() {
    }

    public DiscoveryPacket(String magic, String messageType, DeviceInfo device) {
        this.magic = magic;
        this.messageType = messageType;
        this.device = device;
    }

    public String getMagic() {
        return magic;
    }

    public void setMagic(String magic) {
        this.magic = magic;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public DeviceInfo getDevice() {
        return device;
    }

    public void setDevice(DeviceInfo device) {
        this.device = device;
    }
}
