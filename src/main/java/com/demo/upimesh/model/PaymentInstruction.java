package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 * Inner payment intent. This is what the simulated device signs, then encrypts
 * to the settlement server. Intermediaries never see these fields in the clear.
 *
 * paymentId is the durable financial identity. nonce is extra entropy so two
 * otherwise-identical intents still produce distinct ciphertexts.
 */
public class PaymentInstruction {

    private String paymentId;
    private String senderVpa;
    private String receiverVpa;
    private BigDecimal amount;
    private String nonce;
    private Long issuedAt;
    private Long expiresAt;
    /** Ed25519 signature over the canonical intent fields. Not itself signed. */
    private String senderSignature;

    public PaymentInstruction() {}

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public Long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Long issuedAt) { this.issuedAt = issuedAt; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }

    public String getSenderSignature() { return senderSignature; }
    public void setSenderSignature(String senderSignature) { this.senderSignature = senderSignature; }
}
