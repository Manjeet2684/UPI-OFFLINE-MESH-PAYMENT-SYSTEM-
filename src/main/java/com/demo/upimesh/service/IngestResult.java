package com.demo.upimesh.service;

import org.springframework.http.HttpStatus;

/**
 * Result of one bridge ingest. {@code outcome} is the API contract and always
 * matches persisted financial state when a ledger row exists.
 */
public record IngestResult(
        IngestOutcome outcome,
        String paymentId,
        String packetHash,
        String reason,
        Long transactionId,
        Long attemptId
) {
    public enum IngestOutcome { SETTLED, REJECTED, DUPLICATE, INVALID }

    public HttpStatus httpStatus() {
        return switch (outcome) {
            case SETTLED, REJECTED -> HttpStatus.OK;
            case DUPLICATE -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
    }

    public static IngestResult settled(String paymentId, String hash, Long txId, Long attemptId) {
        return new IngestResult(IngestOutcome.SETTLED, paymentId, hash, null, txId, attemptId);
    }

    public static IngestResult rejected(String paymentId, String hash, String reason, Long txId, Long attemptId) {
        return new IngestResult(IngestOutcome.REJECTED, paymentId, hash, reason, txId, attemptId);
    }

    public static IngestResult duplicate(String paymentId, String hash, Long txId, Long attemptId) {
        return new IngestResult(IngestOutcome.DUPLICATE, paymentId, hash, "duplicate_delivery", txId, attemptId);
    }

    public static IngestResult invalid(String paymentId, String hash, String reason, Long attemptId) {
        return new IngestResult(IngestOutcome.INVALID, paymentId, hash, reason, null, attemptId);
    }
}
