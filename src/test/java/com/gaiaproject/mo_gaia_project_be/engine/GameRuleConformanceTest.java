package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.HexCoord;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.HexState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.completeSetup;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.declineAllLeech;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.findHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rule-audit ↔ 구현 정합성 검증 패스(2026-07-23)에서 발견된 갭의 회귀 테스트 */
class GameRuleConformanceTest {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    @Test
    void 가이아_입장은_기본_1QIC_확장_2QIC_모웨이드_1QIC다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "DAKANIANS"),   // 확장 → 2 QIC
                new GameSetup.PlayerSeat("p2", "MOWEIDS"),     // 확장이지만 1 QIC
                new GameSetup.PlayerSeat("p3", "GEODENS"),     // 기본 → 1 QIC
                new GameSetup.PlayerSeat("p4", "GLEENS")));
        completeSetup(engine, data, state);

        assertEquals(2, buildOnGaiaAndGetQicCost(state, "p1"));
        assertEquals(1, buildOnGaiaAndGetQicCost(state, "p2"));
        assertEquals(1, buildOnGaiaAndGetQicCost(state, "p3"));
    }

    private int buildOnGaiaAndGetQicCost(GameState state, String playerId) {
        PlayerState p = state.player(playerId);
        p.setCredits(50);
        p.setOre(50);
        HexCoord target = HexCoord.parse(findHex(state,
                h -> "GAIA".equals(h.getPlanet()) && !h.hasBuilding()));
        int qicForRange = EngineTestSupport.qicForRange(state, playerId, target, 1);
        p.setQic(30);
        state.setTurnEndPending(false);
        state.setActivePlayer(playerId);
        engine.apply(state, new GameEngine.Submit(playerId, "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qicForRange)));
        declineAllLeech(engine, state);
        return 30 - qicForRange - p.getQic();
    }

    @Test
    void 발타크는_PI_전에는_무료_전진으로도_항해가_오르지_않는다() {
        GameState state = GameSetup.create(data, 3L, List.of(
                new GameSetup.PlayerSeat("p1", "BAL_TAKS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
        completeSetup(engine, data, state);
        PlayerState p1 = state.player("p1");

        // PI 없이 공용 슬롯 타일 + 항해 트랙 지정 → 타일은 획득, 전진만 생략
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", "COMMON_1", "techTrack", "NAVIGATION")));
        assertEquals(1, p1.getTechTiles().size());
        assertEquals(0, p1.track("NAVIGATION"));

        // PI 건설 후에는 전진 가능
        String piKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", "COMMON_2", "techTrack", "NAVIGATION")));
        assertEquals(1, p1.track("NAVIGATION"));
    }

    @Test
    void QIC_아카데미는_라운드_1회_QIC_1_액션이고_발타크는_크레딧_4를_받는다() {
        GameState state = EngineTestSupport.newGame(data, 3L); // p1 기오덴
        completeSetup(engine, data, state);
        PlayerState p1 = state.player("p1");
        String hexKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        state.getHexes().get(hexKey).setBuildingType("ACADEMY");
        state.getHexes().get(hexKey).setAcademyType("QIC");

        int qicBefore = p1.getQic();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_SPECIAL", null,
                Map.of("source", "BUILDING", "id", "ACADEMY_QIC")));
        assertEquals(qicBefore + 1, p1.getQic());

        // 라운드 1회 — 재사용 거부
        state.setTurnEndPending(false);
        EngineException e = assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_SPECIAL", null,
                        Map.of("source", "BUILDING", "id", "ACADEMY_QIC"))));
        assertTrue(e.getMessage().contains("이미 사용한"));

        // 발타크: QIC 대신 크레딧 4
        GameState state2 = GameSetup.create(data, 3L, List.of(
                new GameSetup.PlayerSeat("p1", "BAL_TAKS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
        completeSetup(engine, data, state2);
        PlayerState b = state2.player("p1");
        String key2 = findHex(state2, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        state2.getHexes().get(key2).setBuildingType("ACADEMY");
        state2.getHexes().get(key2).setAcademyType("QIC");

        int creditsBefore = b.getCredits();
        int bQicBefore = b.getQic();
        engine.apply(state2, new GameEngine.Submit("p1", "ACTION_SPECIAL", null,
                Map.of("source", "BUILDING", "id", "ACADEMY_QIC")));
        assertEquals(creditsBefore + 4, b.getCredits());
        assertEquals(bQicBefore, b.getQic());
    }

    @Test
    void 엠바스_교환은_검은행성_광산을_거부한다() {
        GameState state = GameSetup.create(data, 3L, List.of(
                new GameSetup.PlayerSeat("p1", "AMBAS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
        completeSetup(engine, data, state);

        String piKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");
        String bpKey = findHex(state, h -> "EMPTY".equals(h.getPlanet()) && !h.hasBuilding() && h.getShip() == null);
        HexState bp = state.getHexes().get(bpKey);
        bp.setPlanet("BLACK_PLANET");
        bp.setBuildingOwner("p1");
        bp.setBuildingType("BLACK_PLANET_MINE");

        HexCoord mine = HexCoord.parse(bpKey);
        HexCoord pi = HexCoord.parse(piKey);
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_SPECIAL", null,
                        Map.of("source", "FACTION", "id", "PI_ACTION_SWAP_MINE_PI",
                                "mineQ", mine.q(), "mineR", mine.r(), "piQ", pi.q(), "piR", pi.r()))));
    }
}
