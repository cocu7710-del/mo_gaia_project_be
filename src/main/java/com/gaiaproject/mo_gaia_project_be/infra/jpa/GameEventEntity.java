package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * append-only 이벤트 로그 — 게임 진행의 진실의 원천.
 * payload 표준 형식: { input, effects: { resources(from/to), vpLog, board, pushedDecisions } }
 */
@Entity
@Table(name = "game_event")
@IdClass(GameEventEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameEventEntity {

    public record Key(UUID gameId, long seq) implements Serializable {}

    @Id
    @Column(name = "game_id")
    private UUID gameId;

    @Id
    private long seq;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(length = 60)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "undone_by")
    private Long undoneBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
