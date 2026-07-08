package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "game_chat")
@IdClass(GameChatEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameChatEntity {

    public record Key(UUID gameId, long seq) implements Serializable {}

    @Id
    @Column(name = "game_id")
    private UUID gameId;

    @Id
    private long seq;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
