package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {
    List<DeliveryAttempt> findTop50ByOrderByIdDesc();
    List<DeliveryAttempt> findByPaymentIdOrderByIdAsc(String paymentId);
}
