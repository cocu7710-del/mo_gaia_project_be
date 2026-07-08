package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "game_player")
@IdClass(GamePlayerEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GamePlayerEntity {

    public record Key(UUID gameId, UUID userId) implements Serializable {}

    @Id
    @Column(name = "game_id")
    private UUID gameId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "seat_no", nullable = false)
    private short seatNo;

    @Column(length = 20)
    private String faction;

    @Column(name = "bid_vp", nullable = false)
    private short bidVp;

    @Column(name = "final_score")
    private Integer finalScore;

    @Column(name = "final_rank")
    private Short finalRank;
}
