package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.completeSetup;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.enterFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Custom 방 옵션 — 제작자 밸런스 패치 (방 생성 3-X) */
class GameCustomBalanceTest {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    private GameState customGame(long seed) {
        GameState state = GameSetup.create(data, seed, List.of(
                new GameSetup.PlayerSeat("p1", "BAL_TAKS"),
                new GameSetup.PlayerSeat("p2", "LANTIDS"),
                new GameSetup.PlayerSeat("p3", "GEODENS"),
                new GameSetup.PlayerSeat("p4", "TERRANS")), true);
        completeSetup(engine, data, state);
        return state;
    }

    @Test
    void 커스텀_방이_아니면_발타크_함대입장_비용은_7이다() {
        GameState state = GameSetup.create(data, 21L, List.of(
                new GameSetup.PlayerSeat("p1", "BAL_TAKS"),
                new GameSetup.PlayerSeat("p2", "GEODENS"),
                new GameSetup.PlayerSeat("p3", "GLEENS"),
                new GameSetup.PlayerSeat("p4", "TERRANS")), false);
        completeSetup(engine, data, state);
        PlayerState p1 = state.player("p1");
        state.setActivePlayer("p1");
        int vpBefore = p1.getVp();

        enterFleet(engine, state, "p1", "ECLIPSE");

        assertEquals(vpBefore - 7, p1.getVp());
    }

    @Test
    void 커스텀_방이면_발타크_함대입장_비용이_5로_줄어든다() {
        GameState state = customGame(21L);
        PlayerState p1 = state.player("p1");
        state.setActivePlayer("p1");
        int vpBefore = p1.getVp();

        enterFleet(engine, state, "p1", "ECLIPSE");

        assertEquals(vpBefore - 5, p1.getVp());
    }

    @Test
    void 커스텀_방이면_란티다_시작_자원과_파워가_조정된다() {
        // completeSetup 전(1라운드 수입 전) 시점의 순수 시작값만 확인 — 수입(파워토큰 등)과 섞이지 않게
        GameState state = GameSetup.create(data, 21L, List.of(
                new GameSetup.PlayerSeat("p1", "BAL_TAKS"),
                new GameSetup.PlayerSeat("p2", "LANTIDS"),
                new GameSetup.PlayerSeat("p3", "GEODENS"),
                new GameSetup.PlayerSeat("p4", "TERRANS")), true);
        PlayerState p2 = state.player("p2");

        assertEquals(15, p2.getCredits());
        assertEquals(2, p2.getBowl1());
        assertEquals(2, p2.getBowl2());
        assertEquals(0, p2.getBowl3());
    }

    @Test
    void 커스텀_방이면_연방_토큰_고급타일이_뽑히지_않는다() {
        for (long seed = 0; seed < 30; seed++) {
            GameState state = GameSetup.create(data, seed, List.of(
                    new GameSetup.PlayerSeat("p1", "BAL_TAKS"),
                    new GameSetup.PlayerSeat("p2", "LANTIDS"),
                    new GameSetup.PlayerSeat("p3", "GEODENS"),
                    new GameSetup.PlayerSeat("p4", "TERRANS")), true);
            assertFalse(state.getBoard().getAdvTechOffers().containsValue("ADV_TILE_13"),
                    "seed " + seed + "에서 ADV_TILE_13이 뽑혔다");
        }
    }
}
