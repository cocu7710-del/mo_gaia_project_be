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
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.findHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 종족 심화: 브레인스톤 / 하이브 연방 / 프리 액션 / 특수 액션 / 아이타 가이아 페이즈 */
class GameFactionDepthTest {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    /** p1 기오덴, p2 타클론, p3 하이브, p4 아이타 */
    private GameState depthGame() {
        GameState state = GameSetup.create(data, 7L, List.of(
                new GameSetup.PlayerSeat("p1", "GEODENS"),
                new GameSetup.PlayerSeat("p2", "TAKLONS"),
                new GameSetup.PlayerSeat("p3", "IVITS"),
                new GameSetup.PlayerSeat("p4", "ITARS")));
        completeSetup(engine, data, state);
        return state;
    }

    // ═══ 브레인스톤 ═══

    @Test
    void 브레인스톤은_충전_시_우선_이동한다() {
        PlayerState p = new PlayerState();
        p.setBrainstone("BOWL1");
        p.setBowl1(2);
        GameEngine.chargePower(p, 1);
        assertEquals("BOWL2", p.getBrainstone());
        assertEquals(2, p.getBowl1()); // 일반 토큰은 그대로

        GameEngine.chargePower(p, 3); // 토큰 2개 I→II, 그다음 브레인스톤 II→III
        assertEquals("BOWL3", p.getBrainstone());
        assertEquals(0, p.getBowl1());
        assertEquals(2, p.getBowl2());
    }

    @Test
    void 브레인스톤은_3파워_가치로_사용된다() {
        PlayerState p = new PlayerState();
        p.setBrainstone("BOWL3");
        p.setBowl3(1);
        GameEngine.spendPower(p, 4, true); // 브레인스톤 3 + 토큰 1
        assertEquals("BOWL1", p.getBrainstone());
        assertEquals(0, p.getBowl3());
        assertEquals(1, p.getBowl1());

        // 3 미만 비용에 써도 잔여분 소멸
        PlayerState q = new PlayerState();
        q.setBrainstone("BOWL3");
        GameEngine.spendPower(q, 1, true);
        assertEquals("BOWL1", q.getBrainstone());
        assertEquals(0, q.getBowl1()); // 거스름 없음
    }

    @Test
    void 타클론_PI는_리치_수락_시_토큰을_추가로_받고_1파워도_수동이다() {
        GameState state = depthGame();
        // p2(타클론)에 PI 배치
        String piKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        state.getHexes().get(piKey).setBuildingOwner("p2");
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");

        // p1이 그 옆에 광산 건설 → 리치 발생
        PlayerState p1 = state.player("p1");
        p1.setCredits(50);
        p1.setOre(50);
        p1.setQic(50);
        // PI에서 거리 2 이내의 건설 가능 행성 찾기
        String targetKey = state.getHexes().entrySet().stream()
                .filter(e -> {
                    HexState h = e.getValue();
                    return !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()) && !"GAIA".equals(h.getPlanet())
                            && !"TRANSDIM".equals(h.getPlanet()) && !"ASTEROIDS".equals(h.getPlanet())
                            && HexCoord.parse(e.getKey()).distance(HexCoord.parse(piKey)) <= 2;
                })
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        HexCoord target = HexCoord.parse(targetKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        // 타클론 PI 리치 결정 (context.taklonsPi)
        Decision leech = state.getDecisionStack().stream()
                .filter(d -> "LEECH_RESPONSE".equals(d.getType()) && "p2".equals(d.getTarget()))
                .findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(leech.getContext().get("taklonsPi")));

        PlayerState p2 = state.player("p2");
        int tokensBefore = p2.getBowl1() + p2.getBowl2() + p2.getBowl3();
        while (!state.getDecisionStack().isEmpty()) {
            Decision top = state.topDecision();
            engine.apply(state, new GameEngine.Submit(top.getTarget(), "LEECH_RESPONSE", top.getId(),
                    Map.of("accept", true, "order", "TOKEN_FIRST")));
        }
        assertEquals(tokensBefore + 1, p2.getBowl1() + p2.getBowl2() + p2.getBowl3()); // +1 토큰
    }

    // ═══ 하이브 연방 ═══

    @Test
    void 하이브는_QIC_위성으로_누적_연방을_확장한다() {
        GameState state = depthGame();
        PlayerState p3 = state.player("p3");
        p3.setQic(10);
        state.setActivePlayer("p3");

        // 1차 연방: 인접 3헥스 파워 7
        List<HexCoord> cluster = List.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));
        String[] types = {"PLANETARY_INSTITUTE", "ACADEMY", "MINE"};
        for (int i = 0; i < 3; i++) {
            HexState hex = state.getHexes().get(cluster.get(i).key());
            hex.setBuildingOwner("p3");
            hex.setBuildingType(types[i]);
        }
        engine.apply(state, new GameEngine.Submit("p3", "ACTION_FORM_FEDERATION", null,
                Map.of("buildings", cluster.stream().map(HexCoord::key).toList(), "satellites", List.of())));
        Decision tile1 = state.topDecision();
        String supplied = state.getBoard().getFederationSupply().entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).findFirst().orElseThrow();
        engine.apply(state, new GameEngine.Submit("p3", "CHOOSE_FEDERATION_TILE", tile1.getId(), Map.of("tile", supplied)));
        assertEquals(1, p3.getFederationsFormedCount());

        // 2차: 목표 파워 14 — 기존 7 + 신규 7 (인접 확장, 위성 1개 = QIC)
        List<HexCoord> extension = List.of(new HexCoord(1, 1), new HexCoord(2, 0), new HexCoord(2, 1));
        String[] types2 = {"ACADEMY", "PLANETARY_INSTITUTE", "MINE"};
        for (int i = 0; i < 3; i++) {
            HexState hex = state.getHexes().get(extension.get(i).key());
            hex.setBuildingOwner("p3");
            hex.setBuildingType(types2[i]);
        }
        state.setActivePlayer("p3");
        int qicBefore = p3.getQic();
        engine.apply(state, new GameEngine.Submit("p3", "ACTION_FORM_FEDERATION", null,
                Map.of("buildings", extension.stream().map(HexCoord::key).toList(), "satellites", List.of())));
        assertEquals(qicBefore, p3.getQic()); // 위성 없음 → QIC 소모 없음
        assertEquals(1, p3.getFederations().size()); // 단일 그룹 유지
        Decision tile2 = state.topDecision();
        assertEquals("CHOOSE_FEDERATION_TILE", tile2.getType());
    }

    @Test
    void 하이브_2차_연방은_파워_14가_필요하다() {
        GameState state = depthGame();
        PlayerState p3 = state.player("p3");
        p3.setFederationsFormedCount(1); // 이미 1회 결성한 상태로 가정
        state.setActivePlayer("p3");

        List<HexCoord> cluster = List.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));
        String[] types = {"PLANETARY_INSTITUTE", "ACADEMY", "MINE"}; // 파워 7 < 14
        for (int i = 0; i < 3; i++) {
            HexState hex = state.getHexes().get(cluster.get(i).key());
            hex.setBuildingOwner("p3");
            hex.setBuildingType(types[i]);
        }
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p3", "ACTION_FORM_FEDERATION", null,
                        Map.of("buildings", cluster.stream().map(HexCoord::key).toList(), "satellites", List.of()))));
    }

    // ═══ 프리 액션 / 특수 액션 ═══

    @Test
    void 프리_액션은_턴을_소모하지_않는다() {
        GameState state = depthGame();
        PlayerState p1 = state.player("p1");
        int bowl1Before = p1.getBowl1();
        int qicBefore = p1.getQic();
        p1.setBowl3(4);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FREE", null, Map.of("conversion", "PW4_QIC")));

        assertEquals("p1", state.getActivePlayer()); // 턴 유지
        assertEquals(0, p1.getBowl3());
        assertEquals(bowl1Before + 4, p1.getBowl1()); // 사용한 파워 bowl1 복귀
        assertEquals(qicBefore + 1, p1.getQic());
    }

    @Test
    void 아이타는_소각_시_가이아_구역에_토큰이_쌓인다() {
        GameState state = depthGame();
        PlayerState p4 = state.player("p4"); // ITARS: 4/4/0
        state.setActivePlayer("p4");

        engine.apply(state, new GameEngine.Submit("p4", "ACTION_FREE", null, Map.of("conversion", "BURN")));

        assertEquals(2, p4.getBowl2()); // 4 - 2
        assertEquals(1, p4.getBowl3());
        assertEquals(1, p4.getGaiaPower()); // 소각 제거분이 가이아 구역으로
    }

    @Test
    void 기술_타일_액션은_라운드당_1회다() {
        GameState state = depthGame();
        PlayerState p1 = state.player("p1");
        p1.getTechTiles().add("BASIC_TILE_1"); // 액션: 파워 4 차징
        int bowl2Before = p1.getBowl2();

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_SPECIAL", null,
                Map.of("source", "TECH_TILE", "id", "BASIC_TILE_1")));
        assertTrue(p1.getBowl2() != bowl2Before || p1.getBowl3() > 0);

        state.setActivePlayer("p1");
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_SPECIAL", null,
                        Map.of("source", "TECH_TILE", "id", "BASIC_TILE_1"))));
    }

    @Test
    void 하이브_우주정거장은_리치_없이_배치된다() {
        GameState state = depthGame();
        PlayerState p3 = state.player("p3");
        p3.setQic(20);
        state.setActivePlayer("p3");

        // 하이브 건물(의회)에서 도달 가능한 빈 우주 찾기
        String emptyKey = findHex(state, h -> "EMPTY".equals(h.getPlanet()) && !h.hasBuilding()
                && h.getSatelliteOwner() == null && h.getShip() == null);
        HexCoord target = HexCoord.parse(emptyKey);
        int qic = EngineTestSupport.qicForRange(state, "p3", target, 1);

        engine.apply(state, new GameEngine.Submit("p3", "ACTION_SPECIAL", null,
                Map.of("source", "FACTION", "id", "PI_ACTION_SPACE_STATION",
                        "hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("SPACE_STATION", state.getHexes().get(emptyKey).getBuildingType());
        assertTrue(state.getDecisionStack().isEmpty()); // 리치 없음 ✅확정
    }

    // ═══ 아이타 가이아 페이즈 ═══

    @Test
    void 아이타는_라운드_경계에_가이아_파워_4개로_기술_타일을_얻는다() {
        GameState state = depthGame();
        PlayerState p4 = state.player("p4");
        // PI 배치 + 가이아 구역 5토큰
        String piKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        state.getHexes().get(piKey).setBuildingOwner("p4");
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");
        p4.setGaiaPower(5);

        // 전원 패스 → 라운드 경계
        for (int i = 0; i < 4; i++) {
            String player = state.getActivePlayer();
            String freeBooster = state.getBoard().getBoosterHolders().entrySet().stream()
                    .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
            engine.apply(state, new GameEngine.Submit(player, "ACTION_PASS", null, Map.of("booster", freeBooster)));
        }

        // 스택 순서대로 해소: 수입 파워 순서 → 아이타 희생 선택 → 기술 타일 선택
        boolean sacrificed = false;
        boolean tilePicked = false;
        while (!state.getDecisionStack().isEmpty()) {
            Decision top = state.topDecision();
            switch (top.getType()) {
                case "INCOME_POWER_ORDER" -> engine.apply(state, new GameEngine.Submit(
                        top.getTarget(), "INCOME_POWER_ORDER", top.getId(), Map.of("order", "TOKENS_FIRST")));
                case "ITARS_GAIA_TECH" -> {
                    engine.apply(state, new GameEngine.Submit("p4", "ITARS_GAIA_TECH", top.getId(),
                            Map.of("sacrificeCount", 1)));
                    sacrificed = true;
                }
                case "CHOOSE_TECH_TILE" -> {
                    engine.apply(state, new GameEngine.Submit(top.getTarget(), "CHOOSE_TECH_TILE", top.getId(),
                            Map.of("position", "AI")));
                    tilePicked = true;
                }
                default -> throw new IllegalStateException("예상 밖 결정: " + top.getType());
            }
        }
        assertTrue(sacrificed);
        assertTrue(tilePicked);
        assertEquals(1, p4.getTechTiles().size());
    }
}
