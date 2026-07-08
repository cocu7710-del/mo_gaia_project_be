package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.HexCoord;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.HexState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.completeSetup;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.findHex;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.newGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 엣지 룰: 소행성 소각 건설, 6라운드 패스자 리치, 연방 인접 제한·자동 편입, 아이타 6라운드 종료 */
class GameEdgeRulesTest {

    static GameData data;
    static GameEngine engine;

    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    private GameState readyGame() {
        GameState state = newGame(data, 3L);
        completeSetup(engine, data, state);
        return state;
    }

    /** key에 인접하면서 건물·위성·함대가 없는 헥스 */
    private String adjacentFreeHex(GameState state, String key) {
        HexCoord c = HexCoord.parse(key);
        for (int[] d : DIRS) {
            String n = new HexCoord(c.q() + d[0], c.r() + d[1]).key();
            HexState h = state.getHexes().get(n);
            if (h != null && !h.hasBuilding() && h.getSatelliteOwner() == null && h.getShip() == null) {
                return n;
            }
        }
        throw new IllegalStateException("인접 빈 헥스 없음: " + key);
    }

    private String p1MineKey(GameState state) {
        return findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
    }

    // ═══ 소행성 건설 (가이아포머 소각) ═══

    @Test
    void 소행성_건설은_가이아포머를_영구_소각하고_무료다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        String asteroidKey = adjacentFreeHex(state, p1MineKey(state));
        state.getHexes().get(asteroidKey).setPlanet("ASTEROIDS");
        p1.getStock().put("GAIAFORMER", 1);
        int credits = p1.getCredits();
        int ore = p1.getOre();

        HexCoord target = HexCoord.parse(asteroidKey);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", 0)));

        assertEquals("MINE", state.getHexes().get(asteroidKey).getBuildingType());
        assertEquals(0, p1.stockOf("GAIAFORMER")); // 영구 소각
        assertEquals(credits, p1.getCredits());    // 무료 건설
        assertEquals(ore, p1.getOre());
    }

    @Test
    void 가이아포머가_없으면_소행성에_건설할_수_없다() {
        GameState state = readyGame();
        String asteroidKey = adjacentFreeHex(state, p1MineKey(state));
        state.getHexes().get(asteroidKey).setPlanet("ASTEROIDS");
        state.player("p1").getStock().put("GAIAFORMER", 0);

        HexCoord target = HexCoord.parse(asteroidKey);
        EngineException e = assertThrows(EngineException.class, () ->
                engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                        Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", 0))));
        assertTrue(e.getMessage().contains("가이아포머"));
    }

    // ═══ 6라운드 패스자 리치 (edge-cases §4) ═══

    @Test
    void 육라운드_패스자에게는_2파워_이상_리치_오퍼가_생기지_않는다() {
        GameState state = readyGame();
        state.setRound(6);
        PlayerState p2 = state.player("p2");
        p2.setPassed(true);

        String buildKey = adjacentFreeHex(state, p1MineKey(state));
        String tsKey = adjacentFreeHex(state, buildKey);
        state.getHexes().get(buildKey).setPlanet("OXIDE"); // 기오덴 홈 — 테라포밍 불필요
        state.getHexes().get(tsKey).setPlanet("TERRA");
        state.getHexes().get(tsKey).setBuildingOwner("p2");
        state.getHexes().get(tsKey).setBuildingType("TRADING_STATION"); // 파워 2

        int bowl1 = p2.getBowl1();
        int bowl2 = p2.getBowl2();
        HexCoord target = HexCoord.parse(buildKey);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", 0)));

        assertFalse(state.getDecisionStack().stream()
                .anyMatch(d -> "LEECH_RESPONSE".equals(d.getType()) && "p2".equals(d.getTarget())));
        assertEquals(bowl1, p2.getBowl1()); // 충전도 없음
        assertEquals(bowl2, p2.getBowl2());
    }

    @Test
    void 육라운드_패스자도_1파워_리치는_자동으로_받는다() {
        GameState state = readyGame();
        state.setRound(6);
        PlayerState p2 = state.player("p2");
        p2.setPassed(true);

        String buildKey = adjacentFreeHex(state, p1MineKey(state));
        String mineKey = adjacentFreeHex(state, buildKey);
        state.getHexes().get(buildKey).setPlanet("OXIDE");
        state.getHexes().get(mineKey).setPlanet("TERRA");
        state.getHexes().get(mineKey).setBuildingOwner("p2");
        state.getHexes().get(mineKey).setBuildingType("MINE"); // 파워 1

        int bowl1 = p2.getBowl1();
        int bowl2 = p2.getBowl2();
        HexCoord target = HexCoord.parse(buildKey);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", 0)));

        assertFalse(state.getDecisionStack().stream()
                .anyMatch(d -> "LEECH_RESPONSE".equals(d.getType()) && "p2".equals(d.getTarget())));
        assertEquals(bowl1 - 1, p2.getBowl1()); // 1파워 자동 충전
        assertEquals(bowl2 + 1, p2.getBowl2());
    }

    // ═══ 연방: 자동 편입 + 기존 연방 인접 제한 ═══

    private Map<String, Object> federationOf(GameState state, String playerId, String buildingKey) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("buildings", new ArrayList<>(List.of(buildingKey)));
        group.put("satellites", new ArrayList<>());
        state.player(playerId).getFederations().add(group);
        return group;
    }

    @Test
    void 새_건물이_기존_연방에_인접하면_자동_편입된다() {
        GameState state = readyGame();
        String mineKey = p1MineKey(state);
        Map<String, Object> group = federationOf(state, "p1", mineKey);

        String buildKey = adjacentFreeHex(state, mineKey);
        state.getHexes().get(buildKey).setPlanet("OXIDE");
        HexCoord target = HexCoord.parse(buildKey);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", 0)));
        EngineTestSupport.declineAllLeech(engine, state);

        @SuppressWarnings("unchecked")
        List<String> buildings = (List<String>) group.get("buildings");
        assertTrue(buildings.contains(buildKey));
    }

    @Test
    void 기존_연방에_인접한_헥스로는_새_연방을_만들_수_없다() {
        GameState state = readyGame();
        String mineKey = p1MineKey(state);
        federationOf(state, "p1", mineKey);

        String nearKey = adjacentFreeHex(state, mineKey);
        state.getHexes().get(nearKey).setPlanet("TERRA");
        state.getHexes().get(nearKey).setBuildingOwner("p1");
        state.getHexes().get(nearKey).setBuildingType("PLANETARY_INSTITUTE");

        EngineException e = assertThrows(EngineException.class, () ->
                engine.apply(state, new GameEngine.Submit("p1", "ACTION_FORM_FEDERATION", null,
                        Map.of("buildings", List.of(nearKey)))));
        assertTrue(e.getMessage().contains("기존 연방"));
    }

    // ═══ 아이타 가이아 페이즈 — 6라운드 종료에도 해소 후 최종 점수 ═══

    @Test
    void 아이타는_6라운드_종료에도_가이아_기술_타일을_받고_최종_점수로_넘어간다() {
        GameState state = GameSetup.create(data, 3L, List.of(
                new GameSetup.PlayerSeat("p1", "GEODENS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "ITARS")));
        completeSetup(engine, data, state);
        state.setRound(6);

        PlayerState p4 = state.player("p4");
        String piKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        state.getHexes().get(piKey).setBuildingOwner("p4");
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");
        p4.setGaiaPower(4);

        for (int i = 0; i < 4; i++) {
            engine.apply(state, new GameEngine.Submit(state.getActivePlayer(), "ACTION_PASS", null, Map.of()));
        }

        // 게임이 끝나기 전에 아이타 가이아 페이즈가 먼저 해소된다
        assertEquals("PLAYING", state.getPhase());
        assertEquals("ITARS_GAIA_TECH", state.topDecision().getType());

        while (!state.getDecisionStack().isEmpty()) {
            Decision top = state.topDecision();
            switch (top.getType()) {
                case "ITARS_GAIA_TECH" -> engine.apply(state, new GameEngine.Submit("p4", "ITARS_GAIA_TECH",
                        top.getId(), Map.of("sacrificeCount", 1)));
                case "CHOOSE_TECH_TILE" -> engine.apply(state, new GameEngine.Submit(top.getTarget(),
                        "CHOOSE_TECH_TILE", top.getId(), Map.of("position", "AI")));
                default -> throw new IllegalStateException("예상 밖 결정: " + top.getType());
            }
        }

        assertEquals(1, p4.getTechTiles().size());
        assertEquals("FINISHED", state.getPhase());
    }
}
