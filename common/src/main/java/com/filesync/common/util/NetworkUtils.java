package com.filesync.common.util;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本机网络信息工具：枚举 IPv4 地址与广播地址，供设备发现使用。
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    public static List<String> getLocalIPv4Addresses() {
        List<String> result = new ArrayList<>();
        for (NetworkInterface ni : getUsableInterfaces()) {
            for (java.net.InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    result.add(addr.getHostAddress());
                }
            }
        }
        return result;
    }

    public static List<String> getBroadcastAddresses() {
        List<String> result = new ArrayList<>();
        for (NetworkInterface ni : getUsableInterfaces()) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                    result.add(ia.getBroadcast().getHostAddress());
                }
            }
        }
        return result;
    }

    private static List<NetworkInterface> getUsableInterfaces() {
        List<NetworkInterface> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                result.add(ni);
            }
        } catch (SocketException e) {
            throw new IllegalStateException("无法枚举本机网络接口", e);
        }
        return result;
    }
}
