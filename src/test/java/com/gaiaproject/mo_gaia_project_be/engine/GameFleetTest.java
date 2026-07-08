package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.completeSetup;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.newGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lost Fleet: 함대 입장 / 함대 액션 / 인공물 */
class GameFleetTest {

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
    void 함대_입장은_VP_5를_소모하고_입장_순서_보너스를_준다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET_ENTER", null, Map.of("ship", "TF_MARS")));
        assertEquals(5, p1.getVp());                        // 10 - 5, 1번째 입장 보너스 없음
        assertTrue(p1.getFleetProbes().contains("TF_MARS"));

        // 2번째 입장자(p2)는 파워 차징 2
        PlayerState p2 = state.player("p2");
        int bowl2Before = p2.getBowl2();
        engine.apply(state, new GameEngine.Submit("p2", "ACTION_FLEET_ENTER", null, Map.of("ship", "TF_MARS")));
        assertEquals(5, p2.getVp());
        assertEquals(bowl2Before + 2, p2.getBowl2());       // 2/4/0 → 차징 2 → 0/6/0

        // 중복 입장 거부
        state.setActivePlayer("p1");
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_FLEET_ENTER", null, Map.of("ship", "TF_MARS"))));
    }

    @Test
    void 네블라는_입장_시_토큰_1개를_영구_소각한다() {
        GameState state = readyGame();
        PlayerState p4 = state.player("p4"); // NEVLAS
        state.setActivePlayer("p4");
        int tokensBefore = p4.getBowl1() + p4.getBowl2() + p4.getBowl3();

        engine.apply(state, new GameEngine.Submit("p4", "ACTION_FLEET_ENTER", null, Map.of("ship", "ECLIPSE")));

        assertEquals(tokensBefore - 1, p4.getBowl1() + p4.getBowl2() + p4.getBowl3());
    }

    @Test
    void 함대_액션은_입장자만_라운드당_1회_사용할_수_있다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.setQic(10);

        // 미입장 상태에서 사용 거부
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_FLEET", null, Map.of("actionId", "TF_MARS_VP"))));

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET_ENTER", null, Map.of("ship", "TF_MARS")));
        state.setActivePlayer("p1");

        int vpBefore = p1.getVp();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null, Map.of("actionId", "TF_MARS_VP")));
        assertEquals(vpBefore + 2, p1.getVp());             // 기술 타일 0개 → 2VP
        assertEquals(8, p1.getQic());                       // QIC 2 소모

        // 같은 라운드 재사용 거부 (다른 입장자라도)
        PlayerState p2 = state.player("p2");
        p2.getFleetProbes().add("TF_MARS");
        p2.setQic(10);
        state.setActivePlayer("p2");
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p2", "ACTION_FLEET", null, Map.of("actionId", "TF_MARS_VP"))));
    }

    @Test
    void 이클립스_기술_액션은_파워3_지식2로_트랙을_전진시킨다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getFleetProbes().add("ECLIPSE");
        p1.setBowl3(3);
        p1.setKnowledge(5);
        int qicBefore = p1.getQic();

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null,
                Map.of("actionId", "ECLIPSE_TECH", "track", "AI")));

        assertEquals(1, p1.track("AI"));
        assertEquals(qicBefore + 1, p1.getQic());           // AI 레벨 1 보상
        assertEquals(0, p1.getBowl3());
        assertEquals(3, p1.getKnowledge());
    }

    @Test
    void 트와일라잇_인공물_획득은_선착순이다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getFleetProbes().add("TWILIGHT");
        p1.setBowl1(6);
        p1.setBowl2(6);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null, Map.of("actionId", "TWILIGHT_ARTIFACT")));

        Decision top = state.topDecision();
        assertEquals("CHOOSE_ARTIFACT", top.getType());
        assertEquals(6, p1.getBowl1() + p1.getBowl2() + p1.getBowl3()); // 토큰 6개 영구 소각

        String artifact = state.getBoard().getArtifactOffers().entrySet().stream()
                .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_ARTIFACT", top.getId(), Map.of("artifact", artifact)));

        assertTrue(p1.getArtifacts().contains(artifact));
        assertEquals("p1", state.getBoard().getArtifactOffers().get(artifact));

        // 선점된 인공물 재획득 거부
        PlayerState p2 = state.player("p2");
        p2.getFleetProbes().add("TWILIGHT");
        p2.setBowl1(12);
        state.setActivePlayer("p2");
        state.getBoard().getPowerActionsUsedThisRound().clear(); // 테스트 편의: 라운드 제한 해제
        engine.apply(state, new GameEngine.Submit("p2", "ACTION_FLEET", null, Map.of("actionId", "TWILIGHT_ARTIFACT")));
        Decision top2 = state.topDecision();
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p2", "CHOOSE_ARTIFACT", top2.getId(), Map.of("artifact", artifact))));
    }

    @Test
    void 트와일라잇_연방_토큰_재수령이_동작한다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getFleetProbes().add("TWILIGHT");
        p1.setQic(10);
        p1.getFederationTokens().add("FED_TILE_5"); // 광석 2 + 7VP

        int vpBefore = p1.getVp();
        int oreBefore = p1.getOre();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null, Map.of("actionId", "TWILIGHT_FED")));

        Decision top = state.topDecision();
        assertEquals("CHOOSE_FED_TOKEN_REUSE", top.getType());
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_FED_TOKEN_REUSE", top.getId(),
                Map.of("token", "FED_TILE_5")));

        assertEquals(vpBefore + 7, p1.getVp());
        assertEquals(oreBefore + 2, p1.getOre());
        assertEquals(1, p1.getFederationTokens().size());   // 토큰이 늘어나지는 않음
    }

    @Test
    void 모웨이드는_TF마스에_무료_입장한_채_시작한다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "MOWEIDS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "XENOS"),
                new GameSetup.PlayerSeat("p4", "TAKLONS")));
        assertTrue(state.player("p1").getFleetProbes().contains("TF_MARS"));
        assertEquals(10, state.player("p1").getVp()); // VP 차감 없음
    }
}
