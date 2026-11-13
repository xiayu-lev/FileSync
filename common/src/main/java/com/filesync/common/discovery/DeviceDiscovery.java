package com.filesync.common.discovery;

import com.filesync.common.model.DeviceInfo;
import com.filesync.common.model.DiscoveryPacket;
import com.filesync.common.protocol.DiscoveryConstants;
import com.filesync.common.protocol.JsonCodec;
import com.filesync.common.util.NetworkUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台无关的 UDP 设备发现引擎。
 *
 * 工作方式：
 * 1. 周期性向局域网广播 ANNOUNCE；
 * 2. 接收其他设备的 ANNOUNCE 并单播 REPLY，实现快速互相发现；
 * 3. 维护在线设备表，超时未上报的设备自动剔除。
 */
public class DeviceDiscovery {

    private final DeviceInfo self;
    private final Map<String, SeenDevice> devices = new ConcurrentHashMap<>();

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread receiveThread;
    private Thread announceThread;
    private DiscoveryListener listener;

    public DeviceDiscovery(DeviceInfo self) {
        this.self = self;
    }

    public void setListener(DiscoveryListener listener) {
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        // 显式绑定 IPv4 通配地址：某些平台默认只监听 [::]，
        // 会拒绝来自局域网的 IPv4 广播/单播，导致设备互相发现不了。
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new java.net.InetSocketAddress(
                InetAddress.getByName("0.0.0.0"), DiscoveryConstants.DISCOVERY_PORT));
        running = true;

        receiveThread = new Thread(this::receiveLoop, "fs-discovery-rx");
        announceThread = new Thread(this::announceLoop, "fs-discovery-tx");
        receiveThread.setDaemon(true);
        announceThread.setDaemon(true);
        receiveThread.start();
        announceThread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (socket != null) {
            socket.close();
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        if (announceThread != null) {
            announceThread.interrupt();
        }
        devices.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 手动刷新：立刻再广播一次 ANNOUNCE，让在线对端马上回应并回填列表，
     * 不必等下一个周期（默认周期较长，用户点「刷新」时希望立刻看到结果）。
     */
    public void requestAnnounce() {
        if (!running) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                sendBroadcast(DiscoveryConstants.MSG_ANNOUNCE);
            } catch (IOException e) {
                System.err.println("[discovery] 刷新广播失败: " + e.getMessage());
            }
        }, "fs-discovery-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** 返回当前在线设备快照 */
    public List<DeviceInfo> getDevices() {
        List<DeviceInfo> result = new ArrayList<>();
        for (SeenDevice sd : devices.values()) {
            result.add(sd.device);
        }
        return result;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[4096];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                handlePacket(json, packet.getAddress(), packet.getPort());
            } catch (SocketException e) {
                if (running) {
                    System.err.println("[discovery] socket 异常: " + e.getMessage());
                }
            } catch (IOException e) {
                System.err.println("[discovery] 接收失败: " + e.getMessage());
            }
        }
    }

    private void handlePacket(String json, InetAddress sender, int senderPort) {
        try {
            DiscoveryPacket pkt = JsonCodec.fromJson(json, DiscoveryPacket.class);
            if (pkt == null || pkt.getDevice() == null
                    || !DiscoveryConstants.MAGIC.equals(pkt.getMagic())) {
                return;
            }
            DeviceInfo remote = pkt.getDevice();
            if (self.getDeviceId().equals(remote.getDeviceId())) {
                return;
            }
            System.err.println("[discovery] 收到 " + pkt.getMessageType() + " 来自 "
                    + sender.getHostAddress() + ":" + senderPort
                    + " 设备=" + remote.getDeviceName());
            // 以报文实际来源 IP 为准，避免对方自报地址不可达
            remote.setIp(sender.getHostAddress());

            boolean isNew = !devices.containsKey(remote.getDeviceId());
            devices.put(remote.getDeviceId(), new SeenDevice(remote, System.currentTimeMillis()));
            fireEvent(isNew, remote);

            if (DiscoveryConstants.MSG_ANNOUNCE.equals(pkt.getMessageType())) {
                sendPacket(DiscoveryConstants.MSG_REPLY, sender, senderPort);
            }
        } catch (Exception e) {
            System.err.println("[discovery] 忽略无法解析的报文: " + e.getMessage());
        }
    }

    private void announceLoop() {
        while (running) {
            try {
                sendBroadcast(DiscoveryConstants.MSG_ANNOUNCE);
                expireDevices();
                Thread.sleep(DiscoveryConstants.ANNOUNCE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                System.err.println("[discovery] 广播失败: " + e.getMessage());
            }
        }
    }

    private void sendBroadcast(String messageType) throws IOException {
        List<String> targets = new ArrayList<>(NetworkUtils.getBroadcastAddresses());
        targets.add("255.255.255.255");
        String json = JsonCodec.toJson(new DiscoveryPacket(
                DiscoveryConstants.MAGIC, messageType, self));
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        for (String target : targets) {
            DatagramPacket dp = new DatagramPacket(data.clone(), data.length,
                    InetAddress.getByName(target), DiscoveryConstants.DISCOVERY_PORT);
            socket.send(dp);
        }
    }

    private void sendPacket(String messageType, InetAddress target, int port) {
        try {
            String json = JsonCodec.toJson(new DiscoveryPacket(
                    DiscoveryConstants.MAGIC, messageType, self));
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            DatagramPacket dp = new DatagramPacket(data, data.length, target, port);
            socket.send(dp);
        } catch (IOException e) {
            System.err.println("[discovery] 回应失败: " + e.getMessage());
        }
    }

    private void expireDevices() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SeenDevice> entry : devices.entrySet()) {
            if (now - entry.getValue().lastSeen > DiscoveryConstants.DEVICE_EXPIRE_MS) {
                devices.remove(entry.getKey());
                fireLost(entry.getValue().device);
            }
        }
    }

    private void fireEvent(boolean isNew, DeviceInfo device) {
        DiscoveryListener l = listener;
        if (l == null) {
            return;
        }
        try {
            if (isNew) {
                l.onDeviceFound(device);
            } else {
                l.onDeviceUpdated(device);
            }
        } catch (Exception e) {
            System.err.println("[discovery] 监听器异常: " + e.getMessage());
        }
    }

    private void fireLost(DeviceInfo device) {
        DiscoveryListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onDeviceLost(device);
        } catch (Exception e) {
            System.err.println("[discovery] 监听器异常: " + e.getMessage());
        }
    }

    private static final class SeenDevice {
        final DeviceInfo device;
        final long lastSeen;

        SeenDevice(DeviceInfo device, long lastSeen) {
            this.device = device;
            this.lastSeen = lastSeen;
        }
    }
}
