package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Hybrid encryption for untrusted mesh hops:
 *   confidentiality + ciphertext integrity  → RSA-OAEP + AES-256-GCM
 *   binding of outer packet/payment ids     → GCM AAD
 *   sender authenticity                     → Ed25519 (verified after decrypt)
 *
 * Wire format (then Base64):
 *   [ 256 bytes RSA-OAEP wrapped AES key ][ 12 byte IV ][ ciphertext || 16-byte tag ]
 */
@Service
public class HybridCryptoService {

    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int RSA_ENCRYPTED_KEY_BYTES = 256;

    private final SecureRandom rng = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    private final ServerKeyHolder serverKey;

    public HybridCryptoService(ServerKeyHolder serverKey) {
        this.serverKey = serverKey;
    }

    public static byte[] aad(String packetId, String paymentId) {
        String left = packetId == null ? "" : packetId;
        String right = paymentId == null ? "" : paymentId;
        return (left + "|" + right).getBytes(StandardCharsets.UTF_8);
    }

    public String encrypt(PaymentInstruction instruction, PublicKey serverPublicKey,
                          String packetId) throws Exception {
        byte[] plaintext = json.writeValueAsBytes(instruction);
        byte[] aadBytes = aad(packetId, instruction.getPaymentId());

        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(AES_KEY_BITS);
        SecretKey aesKey = kg.generateKey();

        byte[] iv = new byte[GCM_IV_BYTES];
        rng.nextBytes(iv);
        Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
        aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        aes.updateAAD(aadBytes);
        byte[] aesCiphertext = aes.doFinal(plaintext);

        Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.ENCRYPT_MODE, serverPublicKey, oaep);
        byte[] encryptedAesKey = rsa.doFinal(aesKey.getEncoded());

        ByteBuffer buf = ByteBuffer.allocate(encryptedAesKey.length + iv.length + aesCiphertext.length);
        buf.put(encryptedAesKey);
        buf.put(iv);
        buf.put(aesCiphertext);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    public PaymentInstruction decrypt(String base64Ciphertext, String packetId, String paymentId)
            throws Exception {
        byte[] all = Base64.getDecoder().decode(base64Ciphertext);
        if (all.length < RSA_ENCRYPTED_KEY_BYTES + GCM_IV_BYTES + GCM_TAG_BITS / 8) {
            throw new IllegalArgumentException("Ciphertext too short");
        }

        byte[] encryptedAesKey = new byte[RSA_ENCRYPTED_KEY_BYTES];
        byte[] iv = new byte[GCM_IV_BYTES];
        byte[] aesCiphertext = new byte[all.length - RSA_ENCRYPTED_KEY_BYTES - GCM_IV_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(all);
        buf.get(encryptedAesKey);
        buf.get(iv);
        buf.get(aesCiphertext);

        Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.DECRYPT_MODE, serverKey.getPrivateKey(), oaep);
        byte[] aesKeyBytes = rsa.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
        aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        aes.updateAAD(aad(packetId, paymentId));
        byte[] plaintext = aes.doFinal(aesCiphertext);
        return json.readValue(plaintext, PaymentInstruction.class);
    }

    /** SHA-256 of the decoded ciphertext bytes — transport-blob identity. */
    public String hashCiphertext(String base64Ciphertext) throws Exception {
        byte[] raw = Base64.getDecoder().decode(base64Ciphertext);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(raw);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
