package com.demo.upimesh.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Over-the-mesh envelope. Outer fields are visible to forwarding devices.
 *
 * Immutable identifiers (packetId, paymentId) are bound as AES-GCM AAD so they
 * cannot be swapped onto a different ciphertext. TTL, hopCount, path and the
 * delivering bridge are mutable routing metadata and are NOT authenticated.
 */
public class MeshPacket {

    @NotBlank
    private String packetId;

    @NotBlank
    private String paymentId;

    @Min(0)
    private int ttl;

    @Min(0)
    private int hopCount;

    /** Comma-separated device ids this copy has visited. Telemetry only. */
    private String path;

    @NotNull
    private Long createdAt;

    @NotBlank
    private String ciphertext;

    public MeshPacket() {}

    public MeshPacket copyForForward(String nextDeviceId) {
        MeshPacket copy = new MeshPacket();
        copy.packetId = this.packetId;
        copy.paymentId = this.paymentId;
        copy.ttl = this.ttl - 1;
        copy.hopCount = this.hopCount + 1;
        copy.path = (this.path == null || this.path.isBlank())
                ? nextDeviceId
                : this.path + "," + nextDeviceId;
        copy.createdAt = this.createdAt;
        copy.ciphertext = this.ciphertext;
        return copy;
    }

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
