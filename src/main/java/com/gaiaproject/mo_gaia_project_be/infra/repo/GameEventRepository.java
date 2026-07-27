package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameEventRepository extends JpaRepository<GameEventEntity, GameEventEntity.Key> {

    List<GameEventEntity> findByGameIdAndSeqGreaterThanEqualOrderBySeq(UUID gameId, long fromSeq);

    List<GameEventEntity> findByGameIdAndUndoneByIsNullOrderBySeqDesc(UUID gameId);

    void deleteByGameId(UUID gameId);
}
