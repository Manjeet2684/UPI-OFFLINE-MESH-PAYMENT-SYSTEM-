package com.demo.upimesh.service;

import com.demo.upimesh.model.DeliveryAttempt;
import com.demo.upimesh.model.DeliveryAttemptRepository;
import com.demo.upimesh.model.MeshPacket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DeliveryAttemptService {

    private final DeliveryAttemptRepository attempts;

    public DeliveryAttemptService(DeliveryAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * Joins the caller's transaction. Use for SETTLED/REJECTED so an attempt
     * cannot survive if the ledger write rolls back.
     */
    @Transactional
    public DeliveryAttempt record(MeshPacket packet, String packetHash, String bridgeId,
                                  String outcome, String reason, Long transactionId) {
        return insert(packet, packetHash, bridgeId, outcome, reason, transactionId);
    }

    /**
     * Commits independently. Use when the settlement transaction is already
     * doomed (unique-constraint race) or ingest is not in a transaction (INVALID).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryAttempt recordCommitted(MeshPacket packet, String packetHash, String bridgeId,
                                           String outcome, String reason, Long transactionId) {
        return insert(packet, packetHash, bridgeId, outcome, reason, transactionId);
    }

    private DeliveryAttempt insert(MeshPacket packet, String packetHash, String bridgeId,
                                   String outcome, String reason, Long transactionId) {
        DeliveryAttempt row = new DeliveryAttempt();
        row.setPaymentId(packet == null ? null : packet.getPaymentId());
        row.setPacketHash(packetHash == null ? "?" : packetHash);
        row.setPacketId(packet == null ? null : packet.getPacketId());
        row.setBridgeId(bridgeId == null ? "unknown" : bridgeId);
        row.setReceivedAt(Instant.now());
        row.setOutcome(outcome);
        row.setReason(reason);
        row.setTransactionId(transactionId);
        row.setPath(packet == null ? null : packet.getPath());
        row.setHopCount(packet == null ? 0 : packet.getHopCount());
        return attempts.save(row);
    }
}
