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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 종족 심화 2: 란티다 기생 광산 / 모웨이드 링 / 팅커로이드 / 네블라 PI / 브레인스톤 제거 / 위성 최소성 */
class GameFactionDepth2Test {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    /** p1 란티다, p2 모웨이드, p3 팅커로이드, p4 네블라 — 셋업 완료 (팅커로이드 라운드 선택 미해소) */
    private GameState rawGame() {
        GameState state = GameSetup.create(data, 13L, List.of(
                new GameSetup.PlayerSeat("p1", "LANTIDS"),
                new GameSetup.PlayerSeat("p2", "MOWEIDS"),
                new GameSetup.PlayerSeat("p3", "TINKEROIDS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
        completeSetup(engine, data, state);
        return state;
    }

    /** 팅커로이드 라운드 시작 선택까지 해소한 상태 */
    private GameState depthGame() {
        GameState state = rawGame();
        while (!state.getDecisionStack().isEmpty()
                && "TINKEROIDS_ACTION_PICK".equals(state.topDecision().getType())) {
            Decision top = state.topDecision();
            engine.apply(state, new GameEngine.Submit(top.getTarget(), "TINKEROIDS_ACTION_PICK", top.getId(),
                    Map.of("tile", "TINK_QIC_1")));
        }
        return state;
    }

    @Test
    void 란티다는_상대_행성에_기생_광산을_짓는다() {
        GameState state = depthGame();
        PlayerState p1 = state.player("p1");
        p1.setCredits(50);
        p1.setOre(50);
        p1.setQic(50);
        // 상대(p4) 건물이 있는 행성 찾기
        String hostKey = findHex(state, h -> "p4".equals(h.getBuildingOwner()) && h.getParasiteOwner() == null);
        HexCoord target = HexCoord.parse(hostKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        int oreBefore = p1.getOre();

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("p1", state.getHexes().get(hostKey).getParasiteOwner());
        assertEquals("p4", state.getHexes().get(hostKey).getBuildingOwner()); // 원 소유자 유지
        assertEquals(oreBefore - 1, p1.getOre());   // 테라포밍 없이 기본 비용 광석 1만
        assertEquals(5, p1.stockOf("MINE"));        // 재고 소모 (8-2-1)

        // 원 소유자(p4)의 리치: 호스트 건물이 광산(파워 1)이면 자동 수락되어 파워가 순환됨
        PlayerState p4 = state.player("p4");
        assertTrue(p4.getBowl2() + p4.getBowl3() > 0
                || state.getDecisionStack().stream().anyMatch(
                        d -> "LEECH_RESPONSE".equals(d.getType()) && "p4".equals(d.getTarget())));
    }

    @Test
    void 모웨이드_링은_건물_파워를_2_올린다() {
        GameState state = depthGame();
        PlayerState p2 = state.player("p2");
        state.setActivePlayer("p2");
        // 모웨이드 PI 배치 (링 액션은 PI 필요)
        String piKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        state.getHexes().get(piKey).setBuildingOwner("p2");
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");

        // 파워 5 클러스터: PI(3) + 광산(1) + 광산(1) → 링 부착으로 7 달성
        List<HexCoord> cluster = List.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));
        String[] types = {"PLANETARY_INSTITUTE", "MINE", "MINE"};
        for (int i = 0; i < 3; i++) {
            HexState hex = state.getHexes().get(cluster.get(i).key());
            hex.setBuildingOwner("p2");
            hex.setBuildingType(types[i]);
        }
        List<String> keys = cluster.stream().map(HexCoord::key).toList();
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p2", "ACTION_FORM_FEDERATION", null,
                        Map.of("buildings", keys, "satellites", List.of())))); // 5 < 7

        engine.apply(state, new GameEngine.Submit("p2", "ACTION_SPECIAL", null,
                Map.of("source", "FACTION", "id", "PI_ACTION_ATTACH_RING", "hexQ", 1, "hexR", 0)));
        assertTrue(state.getHexes().get("1,0").isRing());

        state.setActivePlayer("p2");
        engine.apply(state, new GameEngine.Submit("p2", "ACTION_FORM_FEDERATION", null,
                Map.of("buildings", keys, "satellites", List.of()))); // 3+3+1 = 7 ✓
        assertEquals("CHOOSE_FEDERATION_TILE", state.topDecision().getType());
    }

    @Test
    void 팅커로이드는_라운드마다_개인_액션을_골라_사용한다() {
        GameState state = rawGame();
        PlayerState p3 = state.player("p3");

        // 1라운드 시작 시 선택 결정이 이미 push됨 (팅커로이드는 셋업에서 PI 배치)
        Decision pick = state.getDecisionStack().stream()
                .filter(d -> "TINKEROIDS_ACTION_PICK".equals(d.getType())).findFirst().orElseThrow();
        engine.apply(state, new GameEngine.Submit("p3", "TINKEROIDS_ACTION_PICK", pick.getId(),
                Map.of("tile", "TINK_POWER_4")));
        assertEquals("TINK_POWER_4", p3.getTinkeroidsCurrentAction());

        // 사용 (메인 액션)
        state.setActivePlayer("p3");
        int bowl2Before = p3.getBowl2();
        int bowl3Before = p3.getBowl3();
        engine.apply(state, new GameEngine.Submit("p3", "ACTION_SPECIAL", null,
                Map.of("source", "FACTION", "id", "PI_PERSONAL_ACTION_TILES")));
        assertNull(p3.getTinkeroidsCurrentAction());
        assertTrue(p3.getBowl2() != bowl2Before || p3.getBowl3() != bowl3Before); // 파순 4 적용

        // 같은 타일은 게임 중 재선택 불가
        assertTrue(p3.getTinkeroidsUsedTiles().contains("TINK_POWER_4"));
    }

    @Test
    void 네블라_PI는_bowl3_토큰을_2파워로_쓴다() {
        GameState state = depthGame();
        PlayerState p4 = state.player("p4");
        state.setActivePlayer("p4");
        String piKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        state.getHexes().get(piKey).setBuildingOwner("p4");
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");
        p4.setBowl3(2);

        engine.apply(state, new GameEngine.Submit("p4", "ACTION_FREE", null, Map.of("conversion", "PW4_QIC")));

        assertEquals(0, p4.getBowl3()); // 4파워 = 토큰 2개
        assertEquals(2, p4.getQic());   // 1 + 1

        // 전용 변환: 2토큰 → 광석 1 + 크레딧 1
        p4.setBowl3(2);
        int oreBefore = p4.getOre();
        engine.apply(state, new GameEngine.Submit("p4", "ACTION_FREE", null, Map.of("conversion", "NEVLAS_2T_ORE_CREDIT")));
        assertEquals(oreBefore + 1, p4.getOre());
        assertEquals(0, p4.getBowl3());
    }

    @Test
    void 브레인스톤은_명시_선택으로만_영구_제거된다() {
        PlayerState p = new PlayerState();
        p.setBrainstone("BOWL1");
        p.setBowl1(2);

        GameEngine.removeTokens(p, 2, false); // 기본: 브레인스톤 보호
        assertEquals("BOWL1", p.getBrainstone());
        assertEquals(0, p.getBowl1());

        p.setBowl1(1);
        GameEngine.removeTokens(p, 2, true);  // 명시 선택 (FE 경고창 이후)
        assertNull(p.getBrainstone());
        assertEquals(0, p.getBowl1());
    }

    @Test
    void 불필요한_위성은_거부된다() {
        GameState state = depthGame();
        PlayerState p4 = state.player("p4");
        state.setActivePlayer("p4");
        p4.setBowl1(10);

        // 인접 클러스터 파워 7 — 위성 없이 연결되는데 위성을 추가하면 거부
        List<HexCoord> cluster = List.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));
        String[] types = {"PLANETARY_INSTITUTE", "ACADEMY", "MINE"};
        for (int i = 0; i < 3; i++) {
            HexState hex = state.getHexes().get(cluster.get(i).key());
            hex.setBuildingOwner("p4");
            hex.setBuildingType(types[i]);
        }
        String redundantSatellite = findHex(state, h -> "EMPTY".equals(h.getPlanet()) && !h.hasBuilding()
                && h.getShip() == null && h.getSatelliteOwner() == null
                && HexCoord.parse("0,0").distance(new HexCoord(0, 0)) == 0
                && new HexCoord(1, 1).key().equals("1,1"));
        // (1,1)은 클러스터에 인접 — 빼도 연결 유지되므로 불필요
        HexState sat = state.getHexes().get("1,1");
        if (sat != null && "EMPTY".equals(sat.getPlanet()) && !sat.hasBuilding()) {
            assertThrows(EngineException.class, () -> engine.apply(state,
                    new GameEngine.Submit("p4", "ACTION_FORM_FEDERATION", null,
                            Map.of("buildings", cluster.stream().map(HexCoord::key).toList(),
                                    "satellites", List.of("1,1")))));
        }
    }
}
