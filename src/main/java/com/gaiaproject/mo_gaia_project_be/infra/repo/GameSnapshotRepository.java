package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameSnapshotRepository extends JpaRepository<GameSnapshotEntity, GameSnapshotEntity.Key> {

    Optional<GameSnapshotEntity> findFirstByGameIdOrderBySeqDesc(UUID gameId);

    Optional<GameSnapshotEntity> findByGameIdAndSeq(UUID gameId, long seq);

    /** 리플레이 — 게임 전체 체크포인트(턴 경계) 목록, 오래된 순 */
    List<GameSnapshotEntity> findByGameIdOrderBySeqAsc(UUID gameId);

    /** 턴 경계가 아닌 임시 스냅샷 정리 (체크포인트만 유지) */
    void deleteByGameIdAndSnapshotType(UUID gameId, String snapshotType);

    void deleteByGameId(UUID gameId);
}
