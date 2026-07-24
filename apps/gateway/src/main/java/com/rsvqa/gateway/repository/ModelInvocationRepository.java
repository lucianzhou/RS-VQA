package com.rsvqa.gateway.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ModelInvocationEntity;

public interface ModelInvocationRepository extends JpaRepository<ModelInvocationEntity, UUID> {
}
