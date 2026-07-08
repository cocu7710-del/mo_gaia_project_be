package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "game_snapshot")
@IdClass(GameSnapshotEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameSnapshotEntity {

    public record Key(UUID gameId, long seq) implements Serializable {}

    @Id
    @Column(name = "game_id")
    private UUID gameId;

    @Id
    private long seq;

    @Column(name = "snapshot_type", nullable = false, length = 15)
    private String snapshotType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
