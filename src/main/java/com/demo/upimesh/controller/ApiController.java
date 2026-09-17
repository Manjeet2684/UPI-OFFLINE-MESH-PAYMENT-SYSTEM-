package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.DeliveryAttempt;
import com.demo.upimesh.model.DeliveryAttemptRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.CorrectnessLabService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IngestResult;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.VirtualDevice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ServerKeyHolder serverKey;
    private final DemoService demo;
    private final MeshSimulatorService mesh;
    private final BridgeIngestionService bridge;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final DeliveryAttemptRepository attemptRepo;
    private final CorrectnessLabService lab;
    private final String ingestToken;

    public ApiController(ServerKeyHolder serverKey,
                         DemoService demo,
                         MeshSimulatorService mesh,
                         BridgeIngestionService bridge,
                         AccountRepository accountRepo,
                         TransactionRepository txRepo,
                         DeliveryAttemptRepository attemptRepo,
                         CorrectnessLabService lab,
                         @Value("${upi.bridge.ingest-token:}") String ingestToken) {
        this.serverKey = serverKey;
        this.demo = demo;
        this.mesh = mesh;
        this.bridge = bridge;
        this.accountRepo = accountRepo;
        this.txRepo = txRepo;
        this.attemptRepo = attemptRepo;
        this.lab = lab;
        this.ingestToken = ingestToken == null ? "" : ingestToken;
    }

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "confidentiality", "RSA-2048-OAEP + AES-256-GCM",
                "senderAuth", "Ed25519 per simulated device",
                "aad", "packetId|paymentId"
        );
    }

    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@Valid @RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount,
                req.ttl == null ? 5 : req.ttl);
        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);
        return ResponseEntity.ok(Map.of(
                "paymentId", packet.getPaymentId(),
                "packetId", packet.getPacketId(),
                "ttl", packet.getTtl(),
                "injectedAt", startDevice,
                "settled", false
        ));
    }

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", d.getDeviceId());
            row.put("hasInternet", d.hasInternet());
            row.put("neighbors", d.getNeighborIds());
            row.put("packetCount", d.packetCount());
            row.put("packetIds", d.getHeldPackets().stream()
                    .map(p -> p.getPacketId().substring(0, 8))
                    .toList());
            row.put("paymentIds", d.getHeldPackets().stream()
                    .map(p -> p.getPaymentId() == null ? "" : p.getPaymentId().substring(0, 8))
                    .toList());
            deviceData.add(row);
        }
        List<Map<String, Object>> inFlight = new ArrayList<>();
        for (MeshSimulatorService.InFlightPayment p : mesh.inFlight()) {
            boolean settled = p.paymentId() != null && txRepo.existsByPaymentId(p.paymentId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", p.paymentId());
            row.put("packetId", p.packetId());
            row.put("path", p.path() == null ? "" : p.path());
            row.put("devices", p.devices());
            row.put("settled", settled);
            row.put("status", settled ? "SETTLED" : "IN_MESH_NOT_SETTLED");
            inFlight.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("devices", deviceData);
        body.put("inFlight", inFlight);
        body.put("forwarding", "controlled flooding on a static graph");
        return body;
    }

    @PostMapping("/mesh/forward")
    public Map<String, Object> meshForward() {
        MeshSimulatorService.ForwardResult r = mesh.forwardOnce();
        return Map.of("transfers", r.transfers(), "deviceCounts", r.deviceCounts());
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossipAlias() {
        return meshForward();
    }

    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        List<Map<String, Object>> results = new ArrayList<>();
        uploads.parallelStream().forEach(up -> {
            IngestResult r = bridge.ingest(up.packet(), up.bridgeNodeId());
            synchronized (results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bridgeNode", up.bridgeNodeId());
                row.put("paymentId", up.packet().getPaymentId());
                row.put("packetId", up.packet().getPacketId().substring(0, 8));
                row.put("path", up.packet().getPath() == null ? "" : up.packet().getPath());
                row.put("outcome", r.outcome().name());
                row.put("reason", r.reason() == null ? "" : r.reason());
                row.put("transactionId", r.transactionId() == null ? -1 : r.transactionId());
                results.add(row);
            }
        });
        return Map.of("uploadsAttempted", uploads.size(), "results", results);
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        return Map.of("status", "mesh cleared (ledger unchanged)");
    }

    /**
     * HTTP trust boundary for simulated bridges. Open when {@code upi.bridge.ingest-token}
     * is empty (local demo). Sender authenticity is still the Ed25519 signature.
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @Valid @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Bridge-Token", required = false) String bridgeToken) {
        if (!ingestToken.isBlank() && !tokenMatches(bridgeToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "unauthorized_bridge"));
        }
        IngestResult r = bridge.ingest(packet, bridgeNodeId);
        return ResponseEntity.status(r.httpStatus()).body(r);
    }

    private boolean tokenMatches(String provided) {
        byte[] expected = ingestToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
        return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> listAccounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Account a : accountRepo.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vpa", a.getVpa());
            row.put("holderName", a.getHolderName());
            row.put("balance", a.getBalance());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }

    @GetMapping("/deliveries")
    public List<DeliveryAttempt> listDeliveries() {
        return attemptRepo.findTop50ByOrderByIdDesc();
    }

    @GetMapping("/payments/{paymentId}/timeline")
    public Map<String, Object> timeline(@PathVariable String paymentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", paymentId);
        body.put("ledger", txRepo.findByPaymentId(paymentId).orElse(null));
        body.put("attempts", attemptRepo.findByPaymentIdOrderByIdAsc(paymentId));
        return body;
    }

    @GetMapping("/lab/scenarios")
    public List<CorrectnessLabService.ScenarioInfo> labScenarios() {
        return lab.catalog();
    }

    @PostMapping("/lab/run/{scenarioId}")
    public CorrectnessLabService.LabReport runLab(@PathVariable String scenarioId) throws Exception {
        return lab.run(scenarioId);
    }

    public static class DemoSendRequest {
        @NotBlank public String senderVpa;
        @NotBlank public String receiverVpa;
        @NotNull @DecimalMin("0.01") public BigDecimal amount;
        public Integer ttl;
        public String startDevice;
    }
}
