package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One simulated phone. Neighbors are other device ids on the static mesh graph.
 * {@code heldPackets} is also the per-device seen set (duplicate suppression).
 */
public class VirtualDevice {

    private final String deviceId;
    private final boolean hasInternet;
    private final Set<String> neighborIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, MeshPacket> heldPackets = new ConcurrentHashMap<>();

    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
    }

    public String getDeviceId() { return deviceId; }
    public boolean hasInternet() { return hasInternet; }

    public void addNeighbor(String neighborId) {
        neighborIds.add(neighborId);
    }

    public Set<String> getNeighborIds() {
        return Set.copyOf(neighborIds);
    }

    public void hold(MeshPacket packet) {
        heldPackets.putIfAbsent(packet.getPacketId(), packet);
    }

    public Collection<MeshPacket> getHeldPackets() {
        return heldPackets.values();
    }

    public boolean holds(String packetId) {
        return heldPackets.containsKey(packetId);
    }

    public int packetCount() {
        return heldPackets.size();
    }

    public void dropExpired(long cutoffEpochMillis) {
        heldPackets.entrySet().removeIf(e -> {
            Long created = e.getValue().getCreatedAt();
            return created != null && created < cutoffEpochMillis;
        });
    }

    public void clear() {
        heldPackets.clear();
    }
}
