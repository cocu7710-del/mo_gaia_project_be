package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 대기 결정 프로젝션 — 진실은 GameState.decisionStack.
 * 조회("내 입력 대기 게임")·타이머 스케줄러·알림용 사본으로, 결정 해소와 같은 트랜잭션에서 유지된다.
 */
@Entity
@Table(name = "game_pending_decision")
@IdClass(GamePendingDecisionEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GamePendingDecisionEntity {

    public record Key(UUID gameId, String decisionId) implements java.io.Serializable {}

    @Id
    @Column(name = "game_id")
    private UUID gameId;

    @Id
    @Column(name = "decision_id", length = 20)
    private String decisionId;

    @Column(name = "stack_order", nullable = false)
    private int stackOrder;

    @Column(name = "target_player", nullable = false, length = 60)
    private String targetPlayer;

    @Column(name = "decision_type", nullable = false, length = 30)
    private String decisionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String context;

    @Column(name = "deadline_at")
    private OffsetDateTime deadlineAt;

    @Column(name = "created_seq", nullable = false)
    private long createdSeq;
}
