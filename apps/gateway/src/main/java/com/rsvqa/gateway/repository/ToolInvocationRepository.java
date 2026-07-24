package com.rsvqa.gateway.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ToolInvocationEntity;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, UUID> {
}
