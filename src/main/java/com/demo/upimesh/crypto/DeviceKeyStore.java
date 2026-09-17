package com.demo.upimesh.crypto;

import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated per-device signing keys. Production equivalent: keys in the phone's
 * secure element, public keys registered with the bank during an online session.
 */
@Component
public class DeviceKeyStore {

    private final ConcurrentHashMap<String, KeyPair> keys = new ConcurrentHashMap<>();

    public String register(String vpa) {
        try {
            KeyPair pair = SenderSignatureService.generateKeyPair();
            keys.put(vpa, pair);
            return SenderSignatureService.publicKeyBase64(pair.getPublic());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate device key for " + vpa, e);
        }
    }

    public PrivateKey privateKey(String vpa) {
        KeyPair pair = keys.get(vpa);
        if (pair == null) {
            throw new IllegalArgumentException("No device key for " + vpa);
        }
        return pair.getPrivate();
    }

    public boolean hasKey(String vpa) {
        return keys.containsKey(vpa);
    }
}
