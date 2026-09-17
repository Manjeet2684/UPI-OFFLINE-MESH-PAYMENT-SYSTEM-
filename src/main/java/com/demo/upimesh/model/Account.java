package com.demo.upimesh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

/**
 * Simulated bank account owned by this demo (not a real bank core).
 * {@code version} remains as a second fence; settlement serializes with
 * SELECT FOR UPDATE rather than relying on optimistic retries.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "vpa", length = 64)
    private String vpa;

    @Column(name = "holder_name", nullable = false, length = 128)
    private String holderName;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    /** X.509-encoded Ed25519 public key (Base64). Modeled as device-held. */
    @Column(name = "ed25519_public_key", nullable = false, length = 128)
    private String ed25519PublicKey;

    @Version
    @Column(name = "version")
    private Long version;

    public Account() {}

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
    }

    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa; }

    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public String getEd25519PublicKey() { return ed25519PublicKey; }
    public void setEd25519PublicKey(String ed25519PublicKey) { this.ed25519PublicKey = ed25519PublicKey; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
