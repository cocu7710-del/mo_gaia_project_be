package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameChatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameChatRepository extends JpaRepository<GameChatEntity, GameChatEntity.Key> {

    Optional<GameChatEntity> findFirstByGameIdOrderBySeqDesc(UUID gameId);

    List<GameChatEntity> findTop100ByGameIdAndSeqGreaterThanOrderBySeq(UUID gameId, long afterSeq);

    /** 방 해산 시 채팅 기록 정리 (FK) */
    void deleteByGameId(UUID gameId);
}
