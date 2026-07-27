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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 라운드 사이클: 패스 → 가이아 페이즈 → 수입 페이즈 → 다음 라운드 / 6라운드 후 최종 점수 */
class GameRoundCycleTest {

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

    private void passAll(GameState state) {
        for (int i = 0; i < 4; i++) {
            String player = state.getActivePlayer();
            Map<String, Object> payload;
            if (state.getRound() < 6) {
                String freeBooster = state.getBoard().getBoosterHolders().entrySet().stream()
                        .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
                payload = Map.of("booster", freeBooster);
            } else {
                payload = Map.of();
            }
            engine.apply(state, new GameEngine.Submit(player, "ACTION_PASS", null, payload));
        }
    }

    private void resolveIncomeDecisions(GameState state) {
        while (!state.getDecisionStack().isEmpty()) {
            Decision top = state.topDecision();
            switch (top.getType()) {
                case "INCOME_POWER_ORDER" -> engine.apply(state, new GameEngine.Submit(
                        top.getTarget(), "INCOME_POWER_ORDER", top.getId(), Map.of("order", "TOKENS_FIRST")));
                case "TERRANS_GAIA_CONVERT" -> engine.apply(state, new GameEngine.Submit(
                        top.getTarget(), "TERRANS_GAIA_CONVERT", top.getId(), Map.of()));
                default -> throw new IllegalStateException("예상 밖 결정: " + top.getType());
            }
        }
    }

    @Test
    void 라운드가_넘어가면_수입이_지급된다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        int oreBefore = p1.getOre();
        int knowledgeBefore = p1.getKnowledge();

        passAll(state);
        resolveIncomeDecisions(state);

        assertEquals(2, state.getRound());
        // 기오덴: 기본 광석1+지식1 + 광산 2개 광석 2 (+부스터)
        assertTrue(p1.getOre() >= oreBefore + 3);
        assertTrue(p1.getKnowledge() >= knowledgeBefore + 1);
    }

    @Test
    void 파워_수입_순서가_결과를_바꾸면_결정이_발생한다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");

        // p1에 의회 배치(수입: 파워 차징 4 + 토큰 1) + 빈 파워 볼 → 순서가 결과를 바꿈
        String planetKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        state.getHexes().get(planetKey).setBuildingOwner("p1");
        state.getHexes().get(planetKey).setBuildingType("PLANETARY_INSTITUTE");
        p1.setBowl1(0);
        p1.setBowl2(0);
        p1.setBowl3(0);

        passAll(state);

        boolean hasOrderDecision = state.getDecisionStack().stream()
                .anyMatch(d -> "INCOME_POWER_ORDER".equals(d.getType()) && "p1".equals(d.getTarget()));
        assertTrue(hasOrderDecision);

        resolveIncomeDecisions(state);
        assertTrue(state.getDecisionStack().isEmpty());
        assertTrue(p1.getBowl2() + p1.getBowl3() > 0); // 토큰 먼저 → 일부가 위 볼로 순환됨
    }

    @Test
    void 가이아_페이즈에_차원변형이_가이아로_변환되고_포머_회수_건설이_가능하다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getTracks().put("GAIA_FORMING", 1);
        p1.getStock().put("GAIAFORMER", 1);
        p1.setBowl1(4);
        p1.setBowl2(4);
        p1.setQic(50);
        p1.setCredits(50);
        p1.setOre(50);

        String transdimKey = findHex(state, h -> "TRANSDIM".equals(h.getPlanet()) && !h.hasBuilding());
        HexCoord target = HexCoord.parse(transdimKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_GAIAFORM", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));
        assertEquals(6, p1.getGaiaPower());
        EngineTestSupport.endTurn(engine, state); // 자유 행동 구간 닫기 (§7.10)

        passAll(state);
        resolveIncomeDecisions(state);

        // 가이아 페이즈: 변환 + 파워 복귀
        assertEquals("GAIA", state.getHexes().get(transdimKey).getPlanet());
        assertEquals(0, p1.getGaiaPower());

        // 자기 포머가 있는 가이아 행성에 광산 건설 → 포머 회수, QIC 불필요
        state.setActivePlayer("p1"); // 테스트 편의: 2라운드 턴 순서는 패스 순(p2부터)이므로 되돌림
        int qicBefore = p1.getQic();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", 0)));
        assertEquals("MINE", state.getHexes().get(transdimKey).getBuildingType());
        assertEquals(1, p1.stockOf("GAIAFORMER"));
        assertEquals(qicBefore, p1.getQic());
    }

    @Test
    void 육라운드_종료_시_최종_점수가_계산된다() {
        GameState state = readyGame();
        state.setRound(6);
        state.getBoard().setFinalScoringTiles(new java.util.ArrayList<>(
                List.of("FINAL_TILE_MOST_BUILDINGS", "FINAL_TILE_PLANET_TYPES")));

        // p1에 건물 하나 추가 → 건물 수 1위
        String extraKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()));
        HexState extra = state.getHexes().get(extraKey);
        extra.setBuildingOwner("p1");
        extra.setBuildingType("MINE");

        int p1Before = state.player("p1").getVp();
        int p2Before = state.player("p2").getVp();

        passAll(state);

        assertEquals("FINISHED", state.getPhase());
        int p1Gain = state.player("p1").getVp() - p1Before;
        int p2Gain = state.player("p2").getVp() - p2Before;
        assertTrue(p1Gain > 0);
        assertTrue(p1Gain > p2Gain); // 건물 1위(18VP)가 반영됨

        // VP 분해 불변식: 카테고리 합계 = 총점 (점수 팝업 데이터 무결성)
        for (String pid : state.getTurnOrder()) {
            PlayerState p = state.player(pid);
            int sum = p.getVpBreakdown().values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(p.getVp(), sum, pid + " vpBreakdown 합계 불일치");
        }
    }
}
