package com.demo.upimesh.service;

import com.demo.upimesh.crypto.DeviceKeyStore;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.SenderSignatureService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seeds demo accounts and simulates an offline device composing a signed,
 * hybrid-encrypted payment packet. In a real deployment this compose path
 * would run on the phone; here DemoService stands in for the device.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final AccountRepository accounts;
    private final HybridCryptoService crypto;
    private final ServerKeyHolder serverKey;
    private final DeviceKeyStore deviceKeys;
    private final long packetMaxAgeSeconds;

    public DemoService(AccountRepository accounts,
                       HybridCryptoService crypto,
                       ServerKeyHolder serverKey,
                       DeviceKeyStore deviceKeys,
                       @Value("${upi.mesh.packet-max-age-seconds:86400}") long packetMaxAgeSeconds) {
        this.accounts = accounts;
        this.crypto = crypto;
        this.serverKey = serverKey;
        this.deviceKeys = deviceKeys;
        this.packetMaxAgeSeconds = packetMaxAgeSeconds;
    }

    @PostConstruct
    public void seedAccounts() {
        if (accounts.count() > 0) {
            accounts.findAll().forEach(this::ensureDeviceKey);
            return;
        }
        saveWithKey(new Account("alice@demo", "Alice", new BigDecimal("5000.00")));
        saveWithKey(new Account("bob@demo", "Bob", new BigDecimal("1000.00")));
        saveWithKey(new Account("carol@demo", "Carol", new BigDecimal("2500.00")));
        saveWithKey(new Account("dave@demo", "Dave", new BigDecimal("500.00")));
        log.info("Seeded 4 demo accounts with device signing keys");
    }

    @Transactional
    public void restoreSeedBalances() {
        setBalance("alice@demo", new BigDecimal("5000.00"));
        setBalance("bob@demo", new BigDecimal("1000.00"));
        setBalance("carol@demo", new BigDecimal("2500.00"));
        setBalance("dave@demo", new BigDecimal("500.00"));
    }

    @Transactional
    public void setBalance(String vpa, BigDecimal balance) {
        Account account = accounts.findById(vpa).orElseThrow();
        account.setBalance(balance);
        accounts.save(account);
    }

    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, int ttl) throws Exception {
        return createPacket(senderVpa, receiverVpa, amount, ttl, true, Instant.now().toEpochMilli());
    }

    public MeshPacket createExpiredPacket(String senderVpa, String receiverVpa,
                                          BigDecimal amount, int ttl) throws Exception {
        long issued = Instant.now().toEpochMilli() - (packetMaxAgeSeconds + 3600) * 1000L;
        return createPacket(senderVpa, receiverVpa, amount, ttl, true, issued);
    }

    public MeshPacket createForgedPacket(String claimedSender, String receiverVpa,
                                         BigDecimal amount, int ttl) throws Exception {
        return createPacket(claimedSender, receiverVpa, amount, ttl, false, Instant.now().toEpochMilli());
    }

    private MeshPacket createPacket(String senderVpa, String receiverVpa, BigDecimal amount,
                                    int ttl, boolean signAsSender, long issuedAt) throws Exception {
        String amountErr = MoneyRules.validateAmount(amount);
        if (amountErr != null) {
            throw new IllegalArgumentException(amountErr);
        }

        String paymentId = UUID.randomUUID().toString();
        String packetId = UUID.randomUUID().toString();
        long expiresAt = issuedAt + packetMaxAgeSeconds * 1000L;

        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setPaymentId(paymentId);
        instruction.setSenderVpa(senderVpa);
        instruction.setReceiverVpa(receiverVpa);
        instruction.setAmount(amount);
        instruction.setNonce(UUID.randomUUID().toString());
        instruction.setIssuedAt(issuedAt);
        instruction.setExpiresAt(expiresAt);

        if (signAsSender) {
            instruction.setSenderSignature(SenderSignatureService.sign(instruction, deviceKeys.privateKey(senderVpa)));
        } else {
            String other = accounts.findAll().stream()
                    .map(Account::getVpa)
                    .filter(v -> !v.equals(senderVpa))
                    .findFirst()
                    .orElse("bob@demo");
            instruction.setSenderSignature(SenderSignatureService.sign(instruction, deviceKeys.privateKey(other)));
        }

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey(), packetId);

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(packetId);
        packet.setPaymentId(paymentId);
        packet.setTtl(ttl);
        packet.setHopCount(0);
        packet.setCreatedAt(issuedAt);
        packet.setCiphertext(ciphertext);
        return packet;
    }

    private void saveWithKey(Account account) {
        account.setEd25519PublicKey(deviceKeys.register(account.getVpa()));
        accounts.save(account);
    }

    private void ensureDeviceKey(Account account) {
        if (!deviceKeys.hasKey(account.getVpa())) {
            account.setEd25519PublicKey(deviceKeys.register(account.getVpa()));
            accounts.save(account);
        }
    }

    public BigDecimal totalBalance() {
        return accounts.findAll().stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Account> allAccounts() {
        return accounts.findAll();
    }
}
