package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Device-held Ed25519 signatures over the payment intent.
 *
 * In this simulation the keypairs live in the same JVM as the server because
 * DemoService is the "phone". Verification on ingest is still real: a packet
 * encrypted to the server without a matching sender signature is rejected.
 */
public final class SenderSignatureService {

    private SenderSignatureService() {}

    public static KeyPair generateKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    public static String publicKeyBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static PublicKey parsePublicKey(String base64) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    public static String canonical(PaymentInstruction instruction) {
        BigDecimal amount = instruction.getAmount().setScale(2, RoundingMode.UNNECESSARY);
        return String.join("|",
                nullToEmpty(instruction.getPaymentId()),
                nullToEmpty(instruction.getSenderVpa()),
                nullToEmpty(instruction.getReceiverVpa()),
                amount.toPlainString(),
                nullToEmpty(instruction.getNonce()),
                String.valueOf(instruction.getIssuedAt()),
                String.valueOf(instruction.getExpiresAt()));
    }

    public static String sign(PaymentInstruction unsigned, PrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(canonical(unsigned).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    public static boolean verify(PaymentInstruction instruction, PublicKey publicKey) throws Exception {
        if (instruction.getSenderSignature() == null || instruction.getSenderSignature().isBlank()) {
            return false;
        }
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(publicKey);
        sig.update(canonical(instruction).getBytes(StandardCharsets.UTF_8));
        return sig.verify(Base64.getDecoder().decode(instruction.getSenderSignature()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
