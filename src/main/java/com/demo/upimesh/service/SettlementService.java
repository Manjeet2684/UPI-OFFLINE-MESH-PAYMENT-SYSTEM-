package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.DeliveryAttempt;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Atomic debit + credit + ledger insert. Duplicate protection is the unique
 * payment_id / packet_hash insert, not an in-memory claim.
 *
 * Concurrent distinct payments on the same account are serialized with
 * SELECT FOR UPDATE on VPAs sorted lexicographically to avoid deadlocks.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final DeliveryAttemptService attempts;
    private final MeshMetrics metrics;

    public SettlementService(AccountRepository accounts,
                             TransactionRepository transactions,
                             DeliveryAttemptService attempts,
                             MeshMetrics metrics) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.attempts = attempts;
        this.metrics = metrics;
    }

    @Transactional
    public IngestResult settle(PaymentInstruction instruction, MeshPacket packet,
                               String packetHash, String bridgeNodeId) {
        String paymentId = instruction.getPaymentId();

        if (alreadyRecorded(paymentId, packetHash)) {
            return duplicate(instruction, packet, packetHash, bridgeNodeId);
        }

        List<String> vpas = Stream.of(instruction.getSenderVpa(), instruction.getReceiverVpa())
                .sorted()
                .toList();
        List<Account> locked = accounts.lockByVpasOrdered(vpas);
        Map<String, Account> byVpa = locked.stream()
                .collect(Collectors.toMap(Account::getVpa, Function.identity()));

        if (locked.size() != 2 || !byVpa.containsKey(instruction.getSenderVpa())
                || !byVpa.containsKey(instruction.getReceiverVpa())) {
            DeliveryAttempt attempt = attempts.record(packet, packetHash, bridgeNodeId,
                    IngestResult.IngestOutcome.INVALID.name(), "unknown_vpa", null);
            metrics.ingest(IngestResult.IngestOutcome.INVALID);
            return IngestResult.invalid(paymentId, packetHash, "unknown_vpa", attempt.getId());
        }

        if (alreadyRecorded(paymentId, packetHash)) {
            return duplicate(instruction, packet, packetHash, bridgeNodeId);
        }

        Account sender = byVpa.get(instruction.getSenderVpa());
        Account receiver = byVpa.get(instruction.getReceiverVpa());
        BigDecimal amount = instruction.getAmount();

        try {
            if (sender.getBalance().compareTo(amount) < 0) {
                Transaction tx = persistLedger(instruction, packetHash, bridgeNodeId,
                        packet.getHopCount(), Transaction.Status.REJECTED);
                DeliveryAttempt attempt = attempts.record(packet, packetHash, bridgeNodeId,
                        IngestResult.IngestOutcome.REJECTED.name(), "insufficient_funds", tx.getId());
                metrics.ingest(IngestResult.IngestOutcome.REJECTED);
                metrics.settlement("REJECTED");
                log.info("REJECTED insufficient funds paymentId={} {} -{}-> {}",
                        paymentId, sender.getVpa(), amount, receiver.getVpa());
                return IngestResult.rejected(paymentId, packetHash, "insufficient_funds",
                        tx.getId(), attempt.getId());
            }

            sender.setBalance(sender.getBalance().subtract(amount));
            receiver.setBalance(receiver.getBalance().add(amount));
            if (sender.getBalance().signum() < 0) {
                throw new IllegalStateException("Negative balance after debit");
            }
            accounts.save(sender);
            accounts.save(receiver);

            Transaction tx = persistLedger(instruction, packetHash, bridgeNodeId,
                    packet.getHopCount(), Transaction.Status.SETTLED);
            DeliveryAttempt attempt = attempts.record(packet, packetHash, bridgeNodeId,
                    IngestResult.IngestOutcome.SETTLED.name(), null, tx.getId());
            metrics.ingest(IngestResult.IngestOutcome.SETTLED);
            metrics.settlement("SETTLED");
            log.info("SETTLED paymentId={} {} {} -> {} bridge={}",
                    paymentId, amount, sender.getVpa(), receiver.getVpa(), bridgeNodeId);
            return IngestResult.settled(paymentId, packetHash, tx.getId(), attempt.getId());
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateDeliveryException(paymentId, packetHash, e);
        }
    }

    private boolean alreadyRecorded(String paymentId, String packetHash) {
        return transactions.existsByPaymentId(paymentId) || transactions.existsByPacketHash(packetHash);
    }

    private IngestResult duplicate(PaymentInstruction instruction, MeshPacket packet,
                                   String packetHash, String bridgeNodeId) {
        Long txId = transactions.findByPaymentId(instruction.getPaymentId())
                .map(Transaction::getId)
                .orElse(null);
        DeliveryAttempt attempt = attempts.record(packet, packetHash, bridgeNodeId,
                IngestResult.IngestOutcome.DUPLICATE.name(), "duplicate_delivery", txId);
        metrics.ingest(IngestResult.IngestOutcome.DUPLICATE);
        return IngestResult.duplicate(instruction.getPaymentId(), packetHash, txId, attempt.getId());
    }

    private Transaction persistLedger(PaymentInstruction instruction, String packetHash,
                                      String bridgeNodeId, int hopCount, Transaction.Status status) {
        Transaction tx = new Transaction();
        tx.setPaymentId(instruction.getPaymentId());
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setIssuedAt(Instant.ofEpochMilli(instruction.getIssuedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(status);
        Transaction saved = transactions.save(tx);
        transactions.flush();
        return saved;
    }
}
