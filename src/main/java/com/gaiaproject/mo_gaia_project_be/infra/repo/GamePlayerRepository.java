package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GamePlayerRepository extends JpaRepository<GamePlayerEntity, GamePlayerEntity.Key> {
    List<GamePlayerEntity> findByGameIdOrderBySeatNo(UUID gameId);
}
