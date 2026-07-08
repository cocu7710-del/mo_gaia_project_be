package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.HexCoord;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.completeSetup;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.declineAllLeech;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.findHex;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.newGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 결정 연쇄 A3: 업그레이드 → 리치 → 기술 타일 → 트랙 전진 → (4→5) 검은행성 */
class GameEngineUpgradeTest {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    private GameState readyGame() {
        GameState state = newGame(data, 3L);
        completeSetup(engine, data, state);
        PlayerState p1 = state.player("p1");
        p1.setCredits(100);
        p1.setOre(100);
        p1.setQic(100);
        return state;
    }

    private void upgrade(GameState state, String hexKey, String to) {
        HexCoord c = HexCoord.parse(hexKey);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_UPGRADE", null,
                Map.of("hexQ", c.q(), "hexR", c.r(), "to", to)));
    }

    @Test
    void 광산을_교역소로_업그레이드하면_재고가_교환된다() {
        GameState state = readyGame();
        String mineKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));

        upgrade(state, mineKey, "TRADING_STATION");

        assertEquals("TRADING_STATION", state.getHexes().get(mineKey).getBuildingType());
        assertEquals(7, state.player("p1").stockOf("MINE"));            // 6 + 반환 1
        assertEquals(3, state.player("p1").stockOf("TRADING_STATION")); // 4 - 1

        declineAllLeech(engine, state);
        assertEquals("p2", state.getActivePlayer()); // 연쇄 종료 → 턴 이동
    }

    @Test
    void 연구소_업그레이드는_기술_타일_선택을_연쇄시킨다() {
        GameState state = readyGame();
        String key = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));

        upgrade(state, key, "TRADING_STATION");
        declineAllLeech(engine, state);
        state.setActivePlayer("p1"); // 테스트 편의: 턴을 되돌림

        upgrade(state, key, "RESEARCH_LAB");
        declineAllLeech(engine, state); // 리치가 먼저 해소되고

        Decision top = state.topDecision(); // 그다음 기술 타일 선택이 열린다
        assertEquals("CHOOSE_TECH_TILE", top.getType());
        assertEquals("p1", top.getTarget());

        String aiTile = state.getBoard().getTechOffers().get("AI");
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", top.getId(),
                Map.of("position", "AI")));

        PlayerState p1 = state.player("p1");
        assertTrue(p1.getTechTiles().contains(aiTile));
        assertEquals(1, p1.track("AI"));            // 트랙 슬롯 타일 → 해당 트랙 무료 전진
        assertTrue(state.getDecisionStack().isEmpty());
        assertEquals("p2", state.getActivePlayer());
    }

    @Test
    void 항해_4에서_타일_전진하면_연방토큰_플립_후_검은행성이_연쇄된다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getTracks().put("NAVIGATION", 4);
        p1.getFederationTokens().add("FED_TILE_2");

        String key = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        upgrade(state, key, "TRADING_STATION");
        declineAllLeech(engine, state);
        state.setActivePlayer("p1");
        upgrade(state, key, "RESEARCH_LAB");
        declineAllLeech(engine, state);

        Decision techDecision = state.topDecision();
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", techDecision.getId(),
                Map.of("position", "NAVIGATION")));

        // 4→5: 토큰 플립 + 점유 + 검은행성 배치 결정
        assertEquals(5, p1.track("NAVIGATION"));
        assertTrue(p1.getUsedFederationTokens().contains("FED_TILE_2"));
        assertEquals("p1", state.getBoard().getTrackLevel5Occupied().get("NAVIGATION"));

        Decision blackPlanet = state.topDecision();
        assertEquals("PLACE_BLACK_PLANET", blackPlanet.getType());

        String emptyKey = findHex(state, h -> "EMPTY".equals(h.getPlanet()) && !h.hasBuilding() && h.getShip() == null);
        HexCoord target = HexCoord.parse(emptyKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 4); // 항해 5 = 거리 4
        engine.apply(state, new GameEngine.Submit("p1", "PLACE_BLACK_PLANET", blackPlanet.getId(),
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("BLACK_PLANET", state.getHexes().get(emptyKey).getPlanet());
        assertEquals("BLACK_PLANET_MINE", state.getHexes().get(emptyKey).getBuildingType());

        declineAllLeech(engine, state);
        assertTrue(state.getDecisionStack().isEmpty());
        assertEquals("p2", state.getActivePlayer());
    }

    @Test
    void 라운드_점수_타일이_광산_건설에_적용된다() {
        GameState state = readyGame();
        state.getBoard().getRoundScoringTiles().set(0, "ROUND_TILE_MINE"); // 1라운드 = 광산 2VP

        PlayerState p1 = state.player("p1");
        String targetKey = findHex(state, h ->
                !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()) && !"TRANSDIM".equals(h.getPlanet())
                        && !"GAIA".equals(h.getPlanet()) && !"ASTEROIDS".equals(h.getPlanet()));
        HexCoord target = HexCoord.parse(targetKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);

        int vpBefore = p1.getVp();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals(vpBefore + 2, p1.getVp());
    }
}
