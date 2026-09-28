package com.gaiaproject.mo_gaia_project_be.infra.repo;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameChatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameChatRepository extends JpaRepository<GameChatEntity, GameChatEntity.Key> {

    Optional<GameChatEntity> findFirstByGameIdOrderBySeqDesc(UUID gameId);

    // 오름차순 + LIMIT 100은 "가장 오래된 100건"이 되어, 채팅이 쌓인 뒤 팝업을 다시 열면
    // 최신 메시지가 안 보이던 버그의 원인이었다 — 내림차순으로 최신 100건을 받아 서비스에서 뒤집는다
    List<GameChatEntity> findTop100ByGameIdAndSeqGreaterThanOrderBySeqDesc(UUID gameId, long afterSeq);

    /** 방 해산 시 채팅 기록 정리 (FK) */
    void deleteByGameId(UUID gameId);
}
