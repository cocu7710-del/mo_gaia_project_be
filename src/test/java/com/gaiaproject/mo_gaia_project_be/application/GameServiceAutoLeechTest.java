package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserSettingsEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** ② 리치 자동 응답 판정 (승점 비용 X 이하 수락 / Y 이상 거절, 무비용은 항상 수동) */
class GameServiceAutoLeechTest {

    private UserSettingsEntity settings(int acceptMax, Integer declineMin) {
        return UserSettingsEntity.builder()
                .leechAutoAcceptMaxVp(acceptMax).leechAutoDeclineMinVp(declineMin).uiPrefs("{}")
                .build();
    }

    @Test
    void 무비용_오퍼는_설정과_무관하게_수동이다() {
        assertNull(GameService.decideAutoLeech(0, settings(5, 1)));
    }

    @Test
    void 설정이_없으면_수동이다() {
        assertNull(GameService.decideAutoLeech(3, null));
    }

    @Test
    void 수락_상한_이하는_자동_수락한다() {
        assertEquals(Boolean.TRUE, GameService.decideAutoLeech(2, settings(3, null)));
    }

    @Test
    void 거절_하한_이상은_자동_거절한다() {
        assertEquals(Boolean.FALSE, GameService.decideAutoLeech(4, settings(1, 4)));
    }

    @Test
    void 두_구간_사이는_수동이다() {
        assertNull(GameService.decideAutoLeech(2, settings(1, 4)));
    }
}
