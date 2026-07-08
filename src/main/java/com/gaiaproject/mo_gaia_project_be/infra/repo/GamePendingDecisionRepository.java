package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePendingDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface GamePendingDecisionRepository
        extends JpaRepository<GamePendingDecisionEntity, GamePendingDecisionEntity.Key> {

    List<GamePendingDecisionEntity> findByGameIdOrderByStackOrder(UUID gameId);

    void deleteByGameId(UUID gameId);

    /** 응답 타이머 초과 결정 (리치 자동 거절 스위퍼용) */
    List<GamePendingDecisionEntity> findByDecisionTypeAndDeadlineAtBefore(String decisionType, OffsetDateTime now);
}
