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
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.newGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 연구 전진 / 파워 액션 / 가이아포밍 / 연방 결성 / 패스 */
class GameEngineActionsTest {

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
        return state;
    }

    @Test
    void 연구_전진은_지식_4를_소모하고_보상을_지급한다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.setKnowledge(10);
        int qicBefore = p1.getQic();

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_RESEARCH", null, Map.of("track", "AI")));

        assertEquals(6, p1.getKnowledge());
        assertEquals(1, p1.track("AI"));
        assertEquals(qicBefore + 1, p1.getQic()); // AI 레벨 1 보상
        EngineTestSupport.endTurn(engine, state);
        assertEquals("p2", state.getActivePlayer());
    }

    @Test
    void 파워_액션은_라운드당_전체_1회다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.setBowl3(7);
        p1.setBowl1(0);
        p1.setBowl2(0);
        int knowledgeBefore = p1.getKnowledge();

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_POWER", null, Map.of("actionId", "PWR_KNOWLEDGE")));

        assertEquals(knowledgeBefore + 3, p1.getKnowledge());
        assertEquals(0, p1.getBowl3());
        assertEquals(7, p1.getBowl1()); // 사용한 파워는 bowl1로 복귀

        // 같은 라운드에 다른 플레이어가 재사용 불가
        PlayerState p2 = state.player("p2");
        p2.setBowl3(7);
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p2", "ACTION_POWER", null, Map.of("actionId", "PWR_KNOWLEDGE"))));
    }

    @Test
    void 파워_테라포밍_액션은_무료_광산_결정을_연쇄시킨다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.setBowl3(5);
        p1.setCredits(50);
        p1.setOre(50);
        p1.setQic(50);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_POWER", null, Map.of("actionId", "PWR_TERRAFORM_2")));

        Decision top = state.topDecision();
        assertEquals("PLACE_MINE", top.getType());

        String targetKey = findHex(state, h -> !h.hasBuilding()
                && !"EMPTY".equals(h.getPlanet()) && !"TRANSDIM".equals(h.getPlanet())
                && !"GAIA".equals(h.getPlanet()) && !"ASTEROIDS".equals(h.getPlanet()));
        HexCoord target = HexCoord.parse(targetKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        engine.apply(state, new GameEngine.Submit("p1", "PLACE_MINE", top.getId(),
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("MINE", state.getHexes().get(targetKey).getBuildingType());
    }

    @Test
    void 가이아포머_배치는_트랙_레벨별_파워를_가이아_구역으로_옮긴다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getTracks().put("GAIA_FORMING", 1);
        p1.getStock().put("GAIAFORMER", 1);
        p1.setBowl1(4);
        p1.setBowl2(4);
        p1.setQic(50);

        String transdimKey = findHex(state, h -> "TRANSDIM".equals(h.getPlanet()) && !h.hasBuilding());
        HexCoord target = HexCoord.parse(transdimKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_GAIAFORM", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("GAIAFORMER", state.getHexes().get(transdimKey).getBuildingType());
        assertEquals(6, p1.getGaiaPower());               // 레벨 1 = 파워 6
        assertEquals(2, p1.getBowl1() + p1.getBowl2() + p1.getBowl3()); // 8 - 6
        assertEquals(0, p1.stockOf("GAIAFORMER"));
    }

    @Test
    void 연방_결성은_파워_7과_연결성을_검증하고_타일_선택을_연쇄시킨다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.setBowl1(10);

        // 테스트용 연방 클러스터: 인접 3헥스에 의회(3)+아카데미(3)+광산(1) = 파워 7
        HexCoord base = new HexCoord(0, 0);
        List<HexCoord> cluster = List.of(base, new HexCoord(1, 0), new HexCoord(0, 1));
        String[] types = {"PLANETARY_INSTITUTE", "ACADEMY", "MINE"};
        for (int i = 0; i < 3; i++) {
            HexState hex = state.getHexes().get(cluster.get(i).key());
            hex.setBuildingOwner("p1");
            hex.setBuildingType(types[i]);
        }
        List<String> buildingKeys = cluster.stream().map(HexCoord::key).toList();

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FORM_FEDERATION", null,
                Map.of("buildings", buildingKeys, "satellites", List.of())));

        Decision top = state.topDecision();
        assertEquals("CHOOSE_FEDERATION_TILE", top.getType());

        String tile = state.getBoard().getFederationSupply().entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).findFirst().orElseThrow();
        int supplyBefore = state.getBoard().getFederationSupply().get(tile);
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_FEDERATION_TILE", top.getId(),
                Map.of("tile", tile)));

        assertTrue(p1.getFederationTokens().contains(tile));
        assertEquals(supplyBefore - 1, state.getBoard().getFederationSupply().get(tile));
        assertEquals(1, p1.getFederations().size());
    }

    @Test
    void 연결되지_않은_연방은_거부된다() {
        GameState state = readyGame();
        HexState a = state.getHexes().get(new HexCoord(0, 0).key());
        HexState b = state.getHexes().get(new HexCoord(5, 5).key());
        a.setBuildingOwner("p1");
        a.setBuildingType("PLANETARY_INSTITUTE");
        if (b != null) {
            b.setBuildingOwner("p1");
            b.setBuildingType("ACADEMY");
        }
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_FORM_FEDERATION", null,
                        Map.of("buildings", List.of("0,0", "5,5"), "satellites", List.of()))));
    }

    @Test
    void 전원_패스하면_패스_순서로_다음_라운드가_시작된다() {
        GameState state = readyGame();

        // p1: 광산 부스터로 교체해 패스 점수 확인
        PlayerState p1 = state.player("p1");
        String oldBooster = p1.getBooster();
        state.getBoard().getBoosterHolders().put(oldBooster, null);
        state.getBoard().getBoosterHolders().put("BOOSTER_4", "p1"); // 광산 1개당 1VP
        p1.setBooster("BOOSTER_4");
        int vpBefore = p1.getVp();

        for (String player : List.of("p1", "p2", "p3", "p4")) {
            String freeBooster = state.getBoard().getBoosterHolders().entrySet().stream()
                    .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
            engine.apply(state, new GameEngine.Submit(player, "ACTION_PASS", null, Map.of("booster", freeBooster)));
        }

        assertEquals(vpBefore + 2, p1.getVp());           // 광산 2개 × 1VP
        assertEquals(2, state.getRound());
        assertEquals(List.of("p1", "p2", "p3", "p4"), state.getTurnOrder()); // 패스 순서 유지
        assertEquals("p1", state.getActivePlayer());
        assertFalse(state.player("p1").isPassed());       // 새 라운드에서 초기화
        assertTrue(state.getBoard().getPassOrder().isEmpty());
    }
}
