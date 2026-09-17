package com.demo.upimesh.service;

/** Raised when a unique payment_id/packet_hash insert loses a race. The settlement TX must roll back. */
public class DuplicateDeliveryException extends RuntimeException {

    private final String paymentId;
    private final String packetHash;

    public DuplicateDeliveryException(String paymentId, String packetHash, Throwable cause) {
        super("Duplicate payment identity", cause);
        this.paymentId = paymentId;
        this.packetHash = packetHash;
    }

    public String paymentId() { return paymentId; }
    public String packetHash() { return packetHash; }
}
