package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserAccountEntity, UUID> {
    Optional<UserAccountEntity> findByNickname(String nickname);

    Optional<UserAccountEntity> findByEmail(String email);
}
