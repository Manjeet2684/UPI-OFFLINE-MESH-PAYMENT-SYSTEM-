package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.SenderSignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.DeliveryAttempt;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Bridge ingest pipeline for an offline payment packet:
 * hash → decrypt+AAD → sender signature → freshness → settle.
 *
 * Delivery is at-least-once. Financial effect is at-most-one per payment_id.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);
    private static final long CLOCK_SKEW_MS = 300_000L;

    private final HybridCryptoService crypto;
    private final SettlementService settlement;
    private final DeliveryAttemptService attempts;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final MeshMetrics metrics;
    private final long maxAgeSeconds;

    public BridgeIngestionService(HybridCryptoService crypto,
                                  SettlementService settlement,
                                  DeliveryAttemptService attempts,
                                  AccountRepository accounts,
                                  TransactionRepository transactions,
                                  MeshMetrics metrics,
                                  @Value("${upi.mesh.packet-max-age-seconds:86400}") long maxAgeSeconds) {
        this.crypto = crypto;
        this.settlement = settlement;
        this.attempts = attempts;
        this.accounts = accounts;
        this.transactions = transactions;
        this.metrics = metrics;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId) {
        String packetHash = "?";
        try {
            if (packet == null || packet.getCiphertext() == null || packet.getCiphertext().isBlank()) {
                return invalid(packet, packetHash, bridgeNodeId, "missing_ciphertext");
            }
            packetHash = crypto.hashCiphertext(packet.getCiphertext());

            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext(), packet.getPacketId(), packet.getPaymentId());
            } catch (Exception e) {
                log.warn("Decryption failed from {}: {}", bridgeNodeId, e.getMessage());
                return invalid(packet, packetHash, bridgeNodeId, "decryption_failed");
            }

            if (!Objects.equals(instruction.getPaymentId(), packet.getPaymentId())) {
                return invalid(packet, packetHash, bridgeNodeId, "payment_id_mismatch");
            }

            String amountError = MoneyRules.validateAmount(instruction.getAmount());
            if (amountError != null) {
                return invalid(packet, packetHash, bridgeNodeId, amountError);
            }
            if (instruction.getSenderVpa() == null || instruction.getReceiverVpa() == null) {
                return invalid(packet, packetHash, bridgeNodeId, "missing_vpa");
            }
            if (instruction.getSenderVpa().equals(instruction.getReceiverVpa())) {
                return invalid(packet, packetHash, bridgeNodeId, "self_transfer");
            }

            String freshness = checkFreshness(instruction);
            if (freshness != null) {
                return invalid(packet, packetHash, bridgeNodeId, freshness);
            }

            Account sender = accounts.findById(instruction.getSenderVpa()).orElse(null);
            if (sender == null) {
                return invalid(packet, packetHash, bridgeNodeId, "unknown_sender");
            }
            try {
                PublicKey senderKey = SenderSignatureService.parsePublicKey(sender.getEd25519PublicKey());
                if (!SenderSignatureService.verify(instruction, senderKey)) {
                    return invalid(packet, packetHash, bridgeNodeId, "invalid_signature");
                }
            } catch (Exception e) {
                return invalid(packet, packetHash, bridgeNodeId, "invalid_signature");
            }

            return settlement.settle(instruction, packet, packetHash, bridgeNodeId);
        } catch (DuplicateDeliveryException e) {
            return duplicateAfterConstraint(packet, e.packetHash(), bridgeNodeId, e.paymentId());
        } catch (Exception e) {
            log.error("Ingestion error from {}: {}", bridgeNodeId, e.getMessage(), e);
            return invalid(packet, packetHash, bridgeNodeId, "internal_error");
        }
    }

    private String checkFreshness(PaymentInstruction instruction) {
        if (instruction.getIssuedAt() == null || instruction.getExpiresAt() == null) {
            return "missing_timestamp";
        }
        long now = Instant.now().toEpochMilli();
        if (instruction.getIssuedAt() - now > CLOCK_SKEW_MS) {
            return "future_dated";
        }
        if (now > instruction.getExpiresAt()) {
            return "stale_packet";
        }
        long maxAgeMs = maxAgeSeconds * 1000L;
        if (now - instruction.getIssuedAt() > maxAgeMs) {
            return "stale_packet";
        }
        return null;
    }

    private IngestResult invalid(MeshPacket packet, String packetHash, String bridgeNodeId, String reason) {
        DeliveryAttempt attempt = attempts.recordCommitted(packet, packetHash, bridgeNodeId,
                IngestResult.IngestOutcome.INVALID.name(), reason, null);
        metrics.ingest(IngestResult.IngestOutcome.INVALID);
        String paymentId = packet == null ? null : packet.getPaymentId();
        return IngestResult.invalid(paymentId, packetHash, reason, attempt.getId());
    }

    private IngestResult duplicateAfterConstraint(MeshPacket packet, String packetHash,
                                                  String bridgeNodeId, String paymentId) {
        Long txId = transactions.findByPaymentId(paymentId).map(tx -> tx.getId()).orElse(null);
        DeliveryAttempt attempt = attempts.recordCommitted(packet, packetHash, bridgeNodeId,
                IngestResult.IngestOutcome.DUPLICATE.name(), "duplicate_delivery", txId);
        metrics.ingest(IngestResult.IngestOutcome.DUPLICATE);
        return IngestResult.duplicate(paymentId, packetHash, txId, attempt.getId());
    }
}
