package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GameSetupTest {

    static GameData data;

    @BeforeAll
    static void load() {
        data = GameData.load();
    }

    private static List<GameSetup.PlayerSeat> seats() {
        return List.of(
                new GameSetup.PlayerSeat("p1", "GEODENS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "XENOS"),
                new GameSetup.PlayerSeat("p4", "TAKLONS"));
    }

    @Test
    void 시작_트랙_레벨1_보상이_셋업에서_지급된다() {
        GameState state = GameSetup.create(data, 11L, seats());

        PlayerState geodens = state.player("p1");
        assertEquals(6, geodens.getOre());                    // 4 + 테라포밍1 보상 2
        assertEquals(1, geodens.track("TERRA_FORMING"));

        PlayerState gleens = state.player("p2");
        assertEquals(5, gleens.getOre());                     // 4 + 항해1 QIC 보상 → 광석 변환
        assertEquals(0, gleens.getQic());

        PlayerState xenos = state.player("p3");
        assertEquals(2, xenos.getQic());                      // 1 + AI1 보상 1

        assertEquals("BOWL1", state.player("p4").getBrainstone());
    }

    @Test
    void 다카니안은_기본1_항해보상1로_QIC_2로_시작한다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "DAKANIANS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "XENOS"),
                new GameSetup.PlayerSeat("p4", "TAKLONS")));
        assertEquals(2, state.player("p1").getQic());         // 기본 1 + 항해1 보상 1 (경제1은 수입 트랙)
        assertEquals(1, state.player("p1").track("ECONOMY"));
    }

    @Test
    void 보드_드로우_구성() {
        GameState state = GameSetup.create(data, 11L, seats());
        assertEquals(6, state.getBoard().getRoundScoringTiles().size());
        assertEquals(2, state.getBoard().getFinalScoringTiles().size());
        assertEquals(7, state.getBoard().getBoosterHolders().size());
        assertEquals(12, state.getBoard().getTechOffers().size());   // 트랙 6 + COMMON 3 + EXPANSION 3
        assertEquals(7, state.getBoard().getAdvTechOffers().size()); // 트랙 6 + COMMON
        assertEquals(6, state.getBoard().getFederationSupply().size());
        assertEquals(4, state.getBoard().getFleetFedTiles().size());
        assertNotNull(state.getBoard().getEconomyOption());
    }

    @Test
    void 초기_배치_큐는_스네이크_순서다() {
        GameState state = GameSetup.create(data, 11L, seats());
        var queue = state.getBoard().getSetupQueue();
        // 1차 p1~p4 광산 + 2차 역순 p4~p1 광산 + 제노스 3번째 = 9
        assertEquals(9, queue.size());
        assertEquals("p1", queue.get(0).get("player"));
        assertEquals("p4", queue.get(3).get("player"));
        assertEquals("p4", queue.get(4).get("player"));      // 역순 시작
        assertEquals("p1", queue.get(7).get("player"));
        assertEquals("p3", queue.get(8).get("player"));      // 제노스 추가 광산

        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals("PLACE_INITIAL_MINE", state.topDecision().getType());
        assertEquals("p1", state.topDecision().getTarget());
    }

    @Test
    void 같은_시드는_같은_셋업이다() {
        GameState a = GameSetup.create(data, 99L, seats());
        GameState b = GameSetup.create(data, 99L, seats());
        assertEquals(a, b);
    }
}
