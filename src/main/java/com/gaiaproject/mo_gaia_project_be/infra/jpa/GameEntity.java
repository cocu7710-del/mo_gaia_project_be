package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "game")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "ruleset_version", nullable = false, length = 20)
    private String rulesetVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String options;

    @Column(name = "rng_seed", nullable = false)
    private long rngSeed;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
