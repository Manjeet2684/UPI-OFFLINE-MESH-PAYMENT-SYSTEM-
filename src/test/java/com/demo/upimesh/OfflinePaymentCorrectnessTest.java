package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.SenderSignatureService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.DeliveryAttemptRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.CorrectnessLabService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IngestResult;
import com.demo.upimesh.service.MeshSimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OfflinePaymentCorrectnessTest {

    @Autowired DemoService demo;
    @Autowired BridgeIngestionService bridge;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired HybridCryptoService crypto;
    @Autowired ServerKeyHolder serverKey;
    @Autowired MeshSimulatorService mesh;
    @Autowired CorrectnessLabService lab;

    @BeforeEach
    void reset() {
        lab.resetFinancialState();
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("123.45"), 5);
        PaymentInstruction decrypted = crypto.decrypt(
                packet.getCiphertext(), packet.getPacketId(), packet.getPaymentId());
        assertEquals("alice@demo", decrypted.getSenderVpa());
        assertEquals("bob@demo", decrypted.getReceiverVpa());
        assertEquals(0, new BigDecimal("123.45").compareTo(decrypted.getAmount()));
        assertEquals(packet.getPaymentId(), decrypted.getPaymentId());
        assertTrue(SenderSignatureService.verify(decrypted,
                SenderSignatureService.parsePublicKey(
                        accounts.findById("alice@demo").orElseThrow().getEd25519PublicKey())));
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("50.00"), 5);
        char[] chars = packet.getCiphertext().toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));
        IngestResult r = bridge.ingest(packet, "bridge-x");
        assertEquals(IngestResult.IngestOutcome.INVALID, r.outcome());
        assertEquals(0, transactions.count());
    }

    @Test
    void aadTamperIsRejected() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("50.00"), 5);
        packet.setPacketId("00000000-0000-0000-0000-000000000000");
        IngestResult r = bridge.ingest(packet, "bridge-x");
        assertEquals(IngestResult.IngestOutcome.INVALID, r.outcome());
        assertEquals("decryption_failed", r.reason());
    }

    @Test
    void duplicatePacketFromThreeBridgesHasOneLedgerEffect() throws Exception {
        BigDecimal aliceBefore = bal("alice@demo");
        BigDecimal bobBefore = bal("bob@demo");
        BigDecimal totalBefore = demo.totalBalance();
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("100.00"), 5);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        Future<?>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            String node = "bridge-" + i;
            futures[i] = pool.submit(() -> {
                start.await();
                IngestResult r = bridge.ingest(packet, node);
                if (r.outcome() == IngestResult.IngestOutcome.SETTLED) settled.incrementAndGet();
                else if (r.outcome() == IngestResult.IngestOutcome.DUPLICATE) duplicates.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, settled.get());
        assertEquals(2, duplicates.get());
        assertEquals(1, transactions.count());
        assertEquals(3, attempts.count());
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), bal("alice@demo"));
        assertEquals(bobBefore.add(new BigDecimal("100.00")), bal("bob@demo"));
        assertEquals(0, totalBefore.compareTo(demo.totalBalance()));
    }

    @Test
    void concurrentDistinctPaymentsBothSettleWhenFundsCover() throws Exception {
        demo.setBalance("alice@demo", new BigDecimal("1000.00"));
        BigDecimal totalBefore = demo.totalBalance();
        MeshPacket a = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("400.00"), 5);
        MeshPacket b = demo.createPacket("alice@demo", "carol@demo", new BigDecimal("500.00"), 5);
        List<IngestResult> results = runConcurrent(a, b);
        long settled = results.stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.SETTLED).count();
        assertEquals(2, settled);
        assertEquals(0, new BigDecimal("100.00").compareTo(bal("alice@demo")));
        assertEquals(0, totalBefore.compareTo(demo.totalBalance()));
        assertEquals(2, transactions.count());
    }

    @Test
    void concurrentDistinctPaymentsNeverOverdraw() throws Exception {
        demo.setBalance("alice@demo", new BigDecimal("1000.00"));
        BigDecimal totalBefore = demo.totalBalance();
        MeshPacket a = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("700.00"), 5);
        MeshPacket b = demo.createPacket("alice@demo", "carol@demo", new BigDecimal("600.00"), 5);
        List<IngestResult> results = runConcurrent(a, b);
        long settled = results.stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.SETTLED).count();
        long rejected = results.stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.REJECTED).count();
        assertEquals(1, settled);
        assertEquals(1, rejected);
        assertTrue(bal("alice@demo").signum() >= 0);
        assertTrue(bal("alice@demo").compareTo(new BigDecimal("1000.00")) < 0);
        assertNotEquals(new BigDecimal("-300.00"), bal("alice@demo"));
        assertEquals(0, totalBefore.compareTo(demo.totalBalance()));
        long settledRows = transactions.findAll().stream()
                .filter(t -> t.getStatus() == Transaction.Status.SETTLED).count();
        long rejectedRows = transactions.findAll().stream()
                .filter(t -> t.getStatus() == Transaction.Status.REJECTED).count();
        assertEquals(1, settledRows);
        assertEquals(1, rejectedRows);
    }

    @Test
    void insufficientFundsIsRejectedNotSettled() throws Exception {
        demo.setBalance("dave@demo", new BigDecimal("10.00"));
        MeshPacket packet = demo.createPacket("dave@demo", "bob@demo", new BigDecimal("50.00"), 5);
        IngestResult r = bridge.ingest(packet, "phone-bridge-1");
        assertEquals(IngestResult.IngestOutcome.REJECTED, r.outcome());
        assertEquals("insufficient_funds", r.reason());
        assertEquals(Transaction.Status.REJECTED, transactions.findAll().get(0).getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(bal("dave@demo")));
    }

    @Test
    void selfTransferIsInvalid() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "alice@demo", new BigDecimal("10.00"), 5);
        IngestResult r = bridge.ingest(packet, "phone-bridge-1");
        assertEquals(IngestResult.IngestOutcome.INVALID, r.outcome());
        assertEquals("self_transfer", r.reason());
        assertEquals(0, transactions.count());
    }

    @Test
    void stalePacketIsRejected() throws Exception {
        MeshPacket packet = demo.createExpiredPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), 5);
        IngestResult r = bridge.ingest(packet, "phone-bridge-1");
        assertEquals(IngestResult.IngestOutcome.INVALID, r.outcome());
        assertEquals("stale_packet", r.reason());
        assertEquals(0, transactions.count());
    }

    @Test
    void forgedSenderIsRejected() throws Exception {
        MeshPacket packet = demo.createForgedPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), 5);
        IngestResult r = bridge.ingest(packet, "phone-bridge-1");
        assertEquals(IngestResult.IngestOutcome.INVALID, r.outcome());
        assertEquals("invalid_signature", r.reason());
        assertEquals(0, transactions.count());
    }

    @Test
    void restartReplayDoesNotSettleTwice() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("25.00"), 5);
        assertEquals(IngestResult.IngestOutcome.SETTLED, bridge.ingest(packet, "b1").outcome());
        IngestResult second = bridge.ingest(packet, "b2");
        assertEquals(IngestResult.IngestOutcome.DUPLICATE, second.outcome());
        assertEquals(1, transactions.count());
        assertEquals(2, attempts.count());
    }

    @Test
    void meshFloodsAlongEdgesToBothBridges() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), 5);
        mesh.inject("phone-alice", packet);
        mesh.forwardOnce();
        assertTrue(mesh.getDevice("phone-relay").holds(packet.getPacketId()));
        assertFalse(mesh.getDevice("phone-bridge-2").holds(packet.getPacketId()));
        mesh.forwardOnce();
        assertTrue(mesh.getDevice("phone-bridge-1").holds(packet.getPacketId()));
        mesh.forwardOnce();
        assertTrue(mesh.getDevice("phone-bridge-2").holds(packet.getPacketId()));
    }

    @Test
    void ttlStopsForwarding() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), 1);
        mesh.inject("phone-alice", packet);
        mesh.forwardOnce();
        assertTrue(mesh.getDevice("phone-relay").holds(packet.getPacketId()));
        mesh.forwardOnce();
        assertFalse(mesh.getDevice("phone-bridge-1").holds(packet.getPacketId()));
        assertFalse(mesh.getDevice("phone-market").holds(packet.getPacketId()));
    }

    @Test
    void paymentStaysUnsettledUntilBridgeUploads() throws Exception {
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), 5);
        mesh.inject("phone-alice", packet);
        mesh.forwardOnce();
        assertEquals(0, transactions.count());
        assertTrue(mesh.inFlight().stream().anyMatch(p -> packet.getPaymentId().equals(p.paymentId())));
        assertEquals(IngestResult.IngestOutcome.SETTLED, bridge.ingest(packet, "phone-bridge-1").outcome());
        assertEquals(1, transactions.count());
    }

    @Test
    void expiredMeshPacketsAreDropped() throws Exception {
        MeshPacket packet = demo.createExpiredPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), 5);
        mesh.inject("phone-alice", packet);
        mesh.forwardOnce();
        assertFalse(mesh.getDevice("phone-alice").holds(packet.getPacketId()));
        assertFalse(mesh.getDevice("phone-relay").holds(packet.getPacketId()));
    }

    @Test
    void labScenariosPass() throws Exception {
        for (var scenario : lab.catalog()) {
            CorrectnessLabService.LabReport report = lab.run(scenario.id());
            assertTrue(report.passed(), scenario.id() + " " + report.failures());
        }
    }

    private List<IngestResult> runConcurrent(MeshPacket a, MeshPacket b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<IngestResult> f1 = pool.submit(() -> {
            start.await();
            return bridge.ingest(a, "phone-bridge-1");
        });
        Future<IngestResult> f2 = pool.submit(() -> {
            start.await();
            return bridge.ingest(b, "phone-bridge-2");
        });
        start.countDown();
        List<IngestResult> out = List.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        return out;
    }

    private BigDecimal bal(String vpa) {
        return accounts.findById(vpa).orElseThrow().getBalance();
    }
}
