package com.gaiaproject.mo_gaia_project_be.infra.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettingsEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "leech_auto_accept_max_vp", nullable = false)
    private int leechAutoAcceptMaxVp;

    @Column(name = "leech_auto_decline_min_vp")
    private Integer leechAutoDeclineMinVp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_prefs", nullable = false)
    private String uiPrefs;
}
