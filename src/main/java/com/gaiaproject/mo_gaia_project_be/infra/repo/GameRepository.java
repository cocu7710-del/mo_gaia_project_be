package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    /** 게임당 단일 라이터 — 결정 처리 트랜잭션은 이 락으로 직렬화된다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GameEntity g where g.id = :id")
    Optional<GameEntity> findByIdForUpdate(@Param("id") UUID id);

    /** 로비 방 목록 (status=WAITING) */
    List<GameEntity> findByStatusOrderByCreatedAtDesc(String status);
}
