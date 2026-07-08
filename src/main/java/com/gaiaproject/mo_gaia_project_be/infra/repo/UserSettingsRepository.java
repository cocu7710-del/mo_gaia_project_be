package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettingsEntity, UUID> {
}
