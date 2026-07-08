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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 수직 슬라이스: 셋업 → 초기 배치 전부 → 광산 건설 → 리치 응답 → 턴 진행 */
class GameEngineTest {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    private GameState newGame(long seed) {
        return GameSetup.create(data, seed, List.of(
                new GameSetup.PlayerSeat("p1", "GEODENS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
    }

    private void playAllInitialMines(GameState state) {
        EngineTestSupport.completeSetup(engine, data, state);
    }

    @Test
    void 초기_배치와_부스터_선택을_마치면_PLAYING으로_전환된다() {
        GameState state = newGame(3L);
        EngineTestSupport.playAllInitialMines(engine, data, state);
        assertEquals("SETUP_BOOSTER", state.getPhase()); // 배치 후 부스터 역순 선택
        assertEquals("p4", state.topDecision().getTarget());

        EngineTestSupport.pickAllBoosters(engine, state);
        assertEquals("PLAYING", state.getPhase());
        assertEquals(1, state.getRound());
        assertEquals("p1", state.getActivePlayer());
        assertEquals(6, state.player("p1").stockOf("MINE"));  // 8 - 초기 2
        assertTrue(state.player("p1").getBooster() != null);
        long buildings = state.getHexes().values().stream().filter(HexState::hasBuilding).count();
        assertEquals(8, buildings);
    }

    @Test
    void 홈_행성이_아니면_초기_배치가_거부된다() {
        GameState state = newGame(3L);
        Decision top = state.topDecision();
        String wrongKey = state.getHexes().entrySet().stream()
                .filter(e -> "SWAMP".equals(e.getValue().getPlanet()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        HexCoord c = HexCoord.parse(wrongKey);
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "PLACE_INITIAL_MINE", top.getId(), Map.of("hexQ", c.q(), "hexR", c.r()))));
    }

    @Test
    void 광산_건설과_리치_연쇄가_동작한다() {
        GameState state = newGame(3L);
        playAllInitialMines(state);

        PlayerState p1 = state.player("p1");
        p1.setCredits(50);
        p1.setOre(50);
        p1.setQic(50);

        // 건설 대상: 건물 없는 일반 행성 (가이아·차원변형·소행성 제외)
        Set<String> excluded = Set.of("EMPTY", "TRANSDIM", "GAIA", "ASTEROIDS");
        String targetKey = state.getHexes().entrySet().stream()
                .filter(e -> !excluded.contains(e.getValue().getPlanet()) && !e.getValue().hasBuilding())
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        HexCoord target = HexCoord.parse(targetKey);

        int dist = state.getHexes().entrySet().stream()
                .filter(e -> "p1".equals(e.getValue().getBuildingOwner()))
                .mapToInt(e -> HexCoord.parse(e.getKey()).distance(target))
                .min().orElseThrow();
        int qicForRange = Math.max(0, (int) Math.ceil((dist - 1) / 2.0));

        // 상대(p2) 교역소를 대상 헥스 거리 2 이내 빈 우주에 배치 → 파워값 2짜리 리치 유발
        String tsKey = state.getHexes().entrySet().stream()
                .filter(e -> "EMPTY".equals(e.getValue().getPlanet()) && !e.getValue().hasBuilding()
                        && HexCoord.parse(e.getKey()).distance(target) <= 2)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        state.getHexes().get(tsKey).setBuildingOwner("p2");
        state.getHexes().get(tsKey).setBuildingType("TRADING_STATION");

        int oreBefore = p1.getOre();
        List<EngineEvent> events = engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qicForRange)));

        // 건설 커밋 + 이벤트 payload에 자원 변화(from→to) 기록
        assertEquals("MINE", state.getHexes().get(targetKey).getBuildingType());
        assertTrue(p1.getOre() < oreBefore);
        assertEquals("ACTION_MINE_BUILT", events.get(0).type());
        @SuppressWarnings("unchecked")
        Map<String, Object> effects = (Map<String, Object>) events.get(0).payload().get("effects");
        @SuppressWarnings("unchecked")
        Map<String, Object> resourceChanges = (Map<String, Object>) effects.get("resources");
        assertTrue(resourceChanges.containsKey("p1"));

        // p2에게 리치 결정(파워 2, VP 1)이 스택에 있어야 함
        assertTrue(state.getDecisionStack().stream()
                .anyMatch(d -> d.getType().equals("LEECH_RESPONSE") && d.getTarget().equals("p2")));

        // 모든 리치를 수락 처리 → 스택 비면 턴이 p2로 넘어감
        int p2VpBefore = state.player("p2").getVp();
        while (!state.getDecisionStack().isEmpty()) {
            Decision top = state.topDecision();
            engine.apply(state, new GameEngine.Submit(top.getTarget(), "LEECH_RESPONSE", top.getId(),
                    Map.of("accept", true)));
        }
        assertTrue(state.player("p2").getVp() < p2VpBefore);  // VP 비용 지불
        assertEquals("p2", state.getActivePlayer());
    }

    @Test
    void 대기_결정이_있으면_메인_액션이_거부된다() {
        GameState state = newGame(3L);
        // 셋업 결정이 대기 중인 상태에서 광산 건설 시도
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null, Map.of("hexQ", 0, "hexR", 0))));
    }
}
