package com.demo.upimesh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per bridge ingest. Many attempts may exist for one payment_id;
 * at most one {@link Transaction} financial effect exists.
 */
@Entity
@Table(name = "delivery_attempts",
        indexes = {
                @Index(name = "idx_attempt_payment", columnList = "payment_id"),
                @Index(name = "idx_attempt_hash", columnList = "packet_hash")
        })
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "packet_hash", nullable = false, length = 64)
    private String packetHash;

    @Column(name = "packet_id", length = 36)
    private String packetId;

    @Column(name = "bridge_id", nullable = false, length = 64)
    private String bridgeId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "reason", length = 128)
    private String reason;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "path", length = 512)
    private String path;

    @Column(name = "hop_count")
    private int hopCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getPacketHash() { return packetHash; }
    public void setPacketHash(String packetHash) { this.packetHash = packetHash; }

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public String getBridgeId() { return bridgeId; }
    public void setBridgeId(String bridgeId) { this.bridgeId = bridgeId; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }
}
