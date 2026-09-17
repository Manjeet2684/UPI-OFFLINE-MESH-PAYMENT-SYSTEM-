package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.DeliveryAttemptRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic experiments against the real ingest/settlement path.
 * Each run reseeds balances and clears ledger/attempts, then asserts invariants.
 */
@Service
public class CorrectnessLabService {

    private final DemoService demo;
    private final BridgeIngestionService ingest;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final DeliveryAttemptRepository attempts;
    private final MeshSimulatorService mesh;

    public CorrectnessLabService(DemoService demo,
                                 BridgeIngestionService ingest,
                                 AccountRepository accounts,
                                 TransactionRepository transactions,
                                 DeliveryAttemptRepository attempts,
                                 MeshSimulatorService mesh) {
        this.demo = demo;
        this.ingest = ingest;
        this.accounts = accounts;
        this.transactions = transactions;
        this.attempts = attempts;
        this.mesh = mesh;
    }

    public LabReport run(String scenarioId) throws Exception {
        return switch (scenarioId) {
            case "two_bridges_duplicate" -> twoBridgesDuplicate();
            case "concurrent_cover_both" -> concurrent(new BigDecimal("1000.00"),
                    new BigDecimal("400.00"), new BigDecimal("500.00"), 2, 0);
            case "concurrent_cover_one" -> concurrent(new BigDecimal("1000.00"),
                    new BigDecimal("700.00"), new BigDecimal("600.00"), 1, 1);
            case "tampered_packet" -> tampered();
            case "forged_sender" -> forged();
            case "aad_tamper" -> aadTamper();
            case "delayed_bridge" -> delayedBridge();
            default -> new LabReport(scenarioId, false, "Unknown scenario", List.of(), List.of(), List.of());
        };
    }

    public List<ScenarioInfo> catalog() {
        return List.of(
                new ScenarioInfo("two_bridges_duplicate",
                        "Same offline payment delivered by two bridges. One SETTLED, one DUPLICATE."),
                new ScenarioInfo("concurrent_cover_both",
                        "Alice has ₹1000. Concurrent ₹400 and ₹500. Both should settle."),
                new ScenarioInfo("concurrent_cover_one",
                        "Alice has ₹1000. Concurrent ₹700 and ₹600. Exactly one settles; never negative."),
                new ScenarioInfo("tampered_packet",
                        "Ciphertext bit-flip is rejected; balances unchanged."),
                new ScenarioInfo("forged_sender",
                        "Packet claiming to be Alice but signed by another device is rejected."),
                new ScenarioInfo("aad_tamper",
                        "Outer packetId swap breaks AES-GCM AAD; no ledger write."),
                new ScenarioInfo("delayed_bridge",
                        "Payment sits in the mesh unsettled until a bridge uploads; then it settles once.")
        );
    }

    private LabReport twoBridgesDuplicate() throws Exception {
        resetFinancialState();
        demo.setBalance("alice@demo", new BigDecimal("1000.00"));
        BigDecimal before = total();
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("200.00"), 5);

        IngestResult a = ingest.ingest(packet, "phone-bridge-1");
        IngestResult b = ingest.ingest(packet, "phone-bridge-2");

        List<String> actual = List.of(
                "bridge-1=" + a.outcome(),
                "bridge-2=" + b.outcome(),
                "ledgerRows=" + transactions.count(),
                "attempts=" + attempts.count(),
                "alice=" + balance("alice@demo")
        );
        List<String> expected = List.of(
                "exactly one SETTLED",
                "exactly one DUPLICATE",
                "one ledger row",
                "two delivery attempts",
                "alice decreased by 200 once",
                "total money conserved"
        );
        List<String> failures = new ArrayList<>();
        long settled = List.of(a, b).stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.SETTLED).count();
        long dup = List.of(a, b).stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.DUPLICATE).count();
        if (settled != 1) failures.add("expected 1 SETTLED, got " + settled);
        if (dup != 1) failures.add("expected 1 DUPLICATE, got " + dup);
        if (transactions.count() != 1) failures.add("expected 1 ledger row");
        if (attempts.count() != 2) failures.add("expected 2 delivery attempts");
        if (balance("alice@demo").compareTo(new BigDecimal("800.00")) != 0) {
            failures.add("alice should be 800");
        }
        if (total().compareTo(before) != 0) failures.add("money not conserved");
        return new LabReport("two_bridges_duplicate", failures.isEmpty(),
                failures.isEmpty() ? "PASS" : "FAIL", expected, actual, failures);
    }

    private LabReport concurrent(BigDecimal aliceStart, BigDecimal aAmt, BigDecimal bAmt,
                                 int expectSettled, int expectRejected) throws Exception {
        resetFinancialState();
        demo.setBalance("alice@demo", aliceStart);
        BigDecimal before = total();
        MeshPacket p1 = demo.createPacket("alice@demo", "bob@demo", aAmt, 5);
        MeshPacket p2 = demo.createPacket("alice@demo", "carol@demo", bAmt, 5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<IngestResult> f1 = pool.submit(() -> {
            start.await();
            return ingest.ingest(p1, "phone-bridge-1");
        });
        Future<IngestResult> f2 = pool.submit(() -> {
            start.await();
            return ingest.ingest(p2, "phone-bridge-2");
        });
        start.countDown();
        IngestResult r1 = f1.get(10, TimeUnit.SECONDS);
        IngestResult r2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        List<IngestResult> results = List.of(r1, r2);
        long settled = results.stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.SETTLED).count();
        long rejected = results.stream().filter(r -> r.outcome() == IngestResult.IngestOutcome.REJECTED).count();
        BigDecimal alice = balance("alice@demo");

        List<String> actual = List.of(
                "r1=" + r1.outcome() + (r1.reason() == null ? "" : "/" + r1.reason()),
                "r2=" + r2.outcome() + (r2.reason() == null ? "" : "/" + r2.reason()),
                "alice=" + alice,
                "total=" + total()
        );
        List<String> expected = List.of(
                expectSettled + " SETTLED",
                expectRejected + " REJECTED",
                "alice >= 0",
                "total money conserved",
                "no two SETTLED summing over Alice's start"
        );
        List<String> failures = new ArrayList<>();
        if (settled != expectSettled) failures.add("settled=" + settled + " expected " + expectSettled);
        if (rejected != expectRejected) failures.add("rejected=" + rejected + " expected " + expectRejected);
        if (alice.signum() < 0) failures.add("negative alice balance");
        if (total().compareTo(before) != 0) failures.add("money not conserved");
        if (alice.compareTo(aliceStart) > 0) failures.add("alice gained money");
        String id = expectSettled == 2 ? "concurrent_cover_both" : "concurrent_cover_one";
        return new LabReport(id, failures.isEmpty(), failures.isEmpty() ? "PASS" : "FAIL",
                expected, actual, failures);
    }

    private LabReport tampered() throws Exception {
        resetFinancialState();
        BigDecimal beforeAlice = balance("alice@demo");
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("50.00"), 5);
        char[] chars = packet.getCiphertext().toCharArray();
        int i = chars.length / 2;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));
        IngestResult r = ingest.ingest(packet, "phone-bridge-1");
        List<String> failures = new ArrayList<>();
        if (r.outcome() != IngestResult.IngestOutcome.INVALID) failures.add("expected INVALID, got " + r.outcome());
        if (transactions.count() != 0) failures.add("tamper must not write ledger");
        if (balance("alice@demo").compareTo(beforeAlice) != 0) failures.add("balances changed");
        return new LabReport("tampered_packet", failures.isEmpty(),
                failures.isEmpty() ? "PASS" : "FAIL",
                List.of("INVALID", "no ledger row", "balances unchanged"),
                List.of("outcome=" + r.outcome(), "reason=" + r.reason(), "alice=" + balance("alice@demo")),
                failures);
    }

    private LabReport forged() throws Exception {
        resetFinancialState();
        BigDecimal beforeAlice = balance("alice@demo");
        MeshPacket packet = demo.createForgedPacket("alice@demo", "bob@demo", new BigDecimal("50.00"), 5);
        IngestResult r = ingest.ingest(packet, "phone-bridge-1");
        List<String> failures = new ArrayList<>();
        if (r.outcome() != IngestResult.IngestOutcome.INVALID) failures.add("expected INVALID, got " + r.outcome());
        if (!"invalid_signature".equals(r.reason())) failures.add("expected invalid_signature, got " + r.reason());
        if (transactions.count() != 0) failures.add("forge must not write ledger");
        if (balance("alice@demo").compareTo(beforeAlice) != 0) failures.add("alice spent without signing");
        return new LabReport("forged_sender", failures.isEmpty(),
                failures.isEmpty() ? "PASS" : "FAIL",
                List.of("INVALID/invalid_signature", "no ledger", "alice unchanged"),
                List.of("outcome=" + r.outcome(), "reason=" + r.reason()),
                failures);
    }

    private LabReport aadTamper() throws Exception {
        resetFinancialState();
        BigDecimal beforeAlice = balance("alice@demo");
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("50.00"), 5);
        packet.setPacketId("00000000-0000-0000-0000-000000000000");
        IngestResult r = ingest.ingest(packet, "phone-bridge-1");
        List<String> failures = new ArrayList<>();
        if (r.outcome() != IngestResult.IngestOutcome.INVALID) failures.add("expected INVALID, got " + r.outcome());
        if (transactions.count() != 0) failures.add("AAD tamper must not write ledger");
        if (balance("alice@demo").compareTo(beforeAlice) != 0) failures.add("balances changed");
        return new LabReport("aad_tamper", failures.isEmpty(),
                failures.isEmpty() ? "PASS" : "FAIL",
                List.of("INVALID", "no ledger", "balances unchanged"),
                List.of("outcome=" + r.outcome(), "reason=" + r.reason()),
                failures);
    }

    private LabReport delayedBridge() throws Exception {
        resetFinancialState();
        demo.setBalance("alice@demo", new BigDecimal("1000.00"));
        BigDecimal aliceBefore = balance("alice@demo");
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo", new BigDecimal("150.00"), 5);
        mesh.inject("phone-alice", packet);
        mesh.forwardOnce();
        List<String> failures = new ArrayList<>();
        if (transactions.count() != 0) failures.add("must not settle before a bridge uploads");
        if (balance("alice@demo").compareTo(aliceBefore) != 0) failures.add("alice changed while packet was still in mesh");
        boolean onMesh = mesh.inFlight().stream().anyMatch(p -> packet.getPaymentId().equals(p.paymentId()));
        if (!onMesh) failures.add("packet should still be in the mesh");
        IngestResult r = ingest.ingest(packet, "phone-bridge-1");
        if (r.outcome() != IngestResult.IngestOutcome.SETTLED) failures.add("expected SETTLED after bridge upload, got " + r.outcome());
        if (transactions.count() != 1) failures.add("expected one ledger row after connectivity");
        if (balance("alice@demo").compareTo(new BigDecimal("850.00")) != 0) failures.add("alice should be 850 after delayed settlement");
        return new LabReport("delayed_bridge", failures.isEmpty(),
                failures.isEmpty() ? "PASS" : "FAIL",
                List.of("unsettled while in mesh", "SETTLED after bridge ingest", "one ledger row"),
                List.of("beforeLedger=" + transactions.count(), "outcome=" + r.outcome(), "alice=" + balance("alice@demo")),
                failures);
    }

    @Transactional
    public void resetFinancialState() {
        attempts.deleteAll();
        transactions.deleteAll();
        demo.restoreSeedBalances();
        mesh.resetMesh();
    }

    private BigDecimal balance(String vpa) {
        return accounts.findById(vpa).map(Account::getBalance).orElseThrow();
    }

    private BigDecimal total() {
        return demo.totalBalance();
    }

    public record ScenarioInfo(String id, String description) {}

    public record LabReport(String scenarioId, boolean passed, String summary,
                            List<String> expected, List<String> actual, List<String> failures) {}
}
