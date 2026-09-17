package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controlled flooding on a small static mesh graph. Not gossip, not BLE.
 *
 * Topology (two paths, two bridges):
 *
 *   phone-alice
 *        |
 *   phone-relay ---- phone-market
 *        |                 |
 *  phone-bridge-1    phone-bridge-2
 */
@Service
public class MeshSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(MeshSimulatorService.class);

    private final Map<String, VirtualDevice> devices = new ConcurrentHashMap<>();
    private final long maxAgeSeconds;

    public MeshSimulatorService(@Value("${upi.mesh.packet-max-age-seconds:86400}") long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
        seedDefaultDevices();
    }

    private void seedDefaultDevices() {
        devices.put("phone-alice", new VirtualDevice("phone-alice", false));
        devices.put("phone-relay", new VirtualDevice("phone-relay", false));
        devices.put("phone-market", new VirtualDevice("phone-market", false));
        devices.put("phone-bridge-1", new VirtualDevice("phone-bridge-1", true));
        devices.put("phone-bridge-2", new VirtualDevice("phone-bridge-2", true));

        link("phone-alice", "phone-relay");
        link("phone-relay", "phone-market");
        link("phone-relay", "phone-bridge-1");
        link("phone-market", "phone-bridge-2");
    }

    private void link(String a, String b) {
        devices.get(a).addNeighbor(b);
        devices.get(b).addNeighbor(a);
    }

    public Collection<VirtualDevice> getDevices() {
        return devices.values();
    }

    public VirtualDevice getDevice(String id) {
        return devices.get(id);
    }

    public void inject(String senderDeviceId, MeshPacket packet) {
        VirtualDevice sender = devices.get(senderDeviceId);
        if (sender == null) {
            throw new IllegalArgumentException("Unknown device: " + senderDeviceId);
        }
        if (packet.getPath() == null || packet.getPath().isBlank()) {
            packet.setPath(senderDeviceId);
        }
        sender.hold(packet);
        log.info("Packet {} / payment {} injected at {} (TTL={})",
                shortId(packet.getPacketId()), shortId(packet.getPaymentId()),
                senderDeviceId, packet.getTtl());
    }

    /**
     * One flooding round: each device forwards packets it currently holds to
     * neighbors that have not seen that packetId, decrementing TTL.
     */
    public ForwardResult forwardOnce() {
        dropExpiredPackets();
        int transfers = 0;
        List<VirtualDevice> deviceList = new ArrayList<>(devices.values());
        Map<String, List<MeshPacket>> snapshot = new HashMap<>();
        for (VirtualDevice d : deviceList) {
            snapshot.put(d.getDeviceId(), new ArrayList<>(d.getHeldPackets()));
        }

        for (VirtualDevice src : deviceList) {
            for (MeshPacket pkt : snapshot.get(src.getDeviceId())) {
                if (pkt.getTtl() <= 0) {
                    continue;
                }
                for (String neighborId : src.getNeighborIds()) {
                    VirtualDevice dst = devices.get(neighborId);
                    if (dst == null || dst.holds(pkt.getPacketId())) {
                        continue;
                    }
                    dst.hold(pkt.copyForForward(dst.getDeviceId()));
                    transfers++;
                }
            }
        }

        log.info("Forward round complete: {} transfers", transfers);
        return new ForwardResult(transfers, snapshotMap());
    }

    /** Alias for older clients; forwarding is controlled flooding, not gossip. */
    @Deprecated
    public ForwardResult gossipOnce() {
        return forwardOnce();
    }

    public Map<String, Integer> snapshotMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String id : List.of("phone-alice", "phone-relay", "phone-market",
                "phone-bridge-1", "phone-bridge-2")) {
            VirtualDevice d = devices.get(id);
            if (d != null) {
                m.put(d.getDeviceId(), d.packetCount());
            }
        }
        return m;
    }

    public List<BridgeUpload> collectBridgeUploads() {
        dropExpiredPackets();
        List<BridgeUpload> out = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            if (!d.hasInternet()) {
                continue;
            }
            for (MeshPacket pkt : d.getHeldPackets()) {
                out.add(new BridgeUpload(d.getDeviceId(), pkt));
            }
        }
        return out;
    }

    public void resetMesh() {
        devices.values().forEach(VirtualDevice::clear);
    }

    public List<InFlightPayment> inFlight() {
        dropExpiredPackets();
        Map<String, InFlightPayment> byPayment = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            for (MeshPacket pkt : d.getHeldPackets()) {
                InFlightPayment row = byPayment.computeIfAbsent(pkt.getPaymentId(),
                        id -> new InFlightPayment(id, pkt.getPacketId(), pkt.getPath(), new ArrayList<>()));
                row.devices().add(d.getDeviceId());
            }
        }
        return new ArrayList<>(byPayment.values());
    }

    private void dropExpiredPackets() {
        long cutoff = Instant.now().toEpochMilli() - maxAgeSeconds * 1000L;
        devices.values().forEach(d -> d.dropExpired(cutoff));
    }

    private static String shortId(String id) {
        if (id == null || id.length() < 8) {
            return String.valueOf(id);
        }
        return id.substring(0, 8);
    }

    public record ForwardResult(int transfers, Map<String, Integer> deviceCounts) {}
    public record BridgeUpload(String bridgeNodeId, MeshPacket packet) {}
    public record InFlightPayment(String paymentId, String packetId, String path, List<String> devices) {}
}
