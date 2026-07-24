package com.rsvqa.gateway.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.UserSettingEntity;

public interface UserSettingRepository extends JpaRepository<UserSettingEntity, UUID> {
    Optional<UserSettingEntity> findByUserId(UUID userId);
}
