package com.speakpath.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

    Optional<UserDevice> findByRefreshTokenHash(String refreshTokenHash);

    List<UserDevice> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
