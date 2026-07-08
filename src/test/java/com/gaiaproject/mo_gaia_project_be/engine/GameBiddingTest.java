package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.completeSetup;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.newGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 종족 비딩 (SETUP_BID) — decision-flows §4 순차 경매 */
class GameBiddingTest {

    static GameData data;
    static GameEngine engine;

    @BeforeAll
    static void load() {
        data = GameData.load();
        engine = new GameEngine(data);
    }

    private void bid(GameState state, String player, int amount) {
        Decision top = state.topDecision();
        engine.apply(state, new GameEngine.Submit(player, "BID_FACTION", top.getId(), Map.of("bid", amount)));
    }

    private void pass(GameState state, String player) {
        Decision top = state.topDecision();
        engine.apply(state, new GameEngine.Submit(player, "BID_FACTION", top.getId(), Map.of("pass", true)));
    }

    private void choose(GameState state, String player, String faction) {
        Decision top = state.topDecision();
        engine.apply(state, new GameEngine.Submit(player, "CHOOSE_FACTION", top.getId(), Map.of("faction", faction)));
    }

    @Test
    void 경매로_종족과_턴_순서가_확정된다() {
        GameState state = GameSetup.createWithBidding(data, 3L, List.of("p1", "p2", "p3", "p4"));
        assertEquals("SETUP_BID", state.getPhase());
        assertEquals("BID_FACTION", state.topDecision().getType());
        assertEquals("p1", state.topDecision().getTarget());

        // 1차 경매: p2가 2로 낙찰
        bid(state, "p1", 0);
        bid(state, "p2", 2);
        pass(state, "p3");
        pass(state, "p4");
        pass(state, "p1");
        assertEquals("CHOOSE_FACTION", state.topDecision().getType());
        assertEquals("p2", state.topDecision().getTarget());
        choose(state, "p2", "TAKLONS");
        assertEquals(2, state.player("p2").getBidVp());

        // 2차 경매: 남은 [p1, p3, p4], p1부터 — p1이 0으로 낙찰
        assertEquals("p1", state.topDecision().getTarget());
        bid(state, "p1", 0);
        pass(state, "p3");
        pass(state, "p4");
        choose(state, "p1", "GEODENS");

        // 3차 경매: [p3, p4] — p3이 1로 낙찰
        bid(state, "p3", 1);
        pass(state, "p4");
        choose(state, "p3", "GLEENS");

        // 마지막 p4는 경매 없이 비딩 0으로 선택
        assertEquals("CHOOSE_FACTION", state.topDecision().getType());
        assertEquals("p4", state.topDecision().getTarget());
        choose(state, "p4", "TERRANS");

        // 낙찰 순서 = 턴 순서, 초기 배치 페이즈로 전환
        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals(List.of("p2", "p1", "p3", "p4"), state.getTurnOrder());
        assertEquals("PLACE_INITIAL_MINE", state.topDecision().getType());
        assertEquals("p2", state.topDecision().getTarget());
        assertFalse(state.getBoard().getFactionPool().contains("TAKLONS"));

        // 초기 배치~부스터까지 정상 진행
        completeSetup(engine, data, state);
        assertEquals("PLAYING", state.getPhase());
    }

    @Test
    void 현재가_이하_비딩과_첫_발언자_패스는_거부된다() {
        GameState state = GameSetup.createWithBidding(data, 3L, List.of("p1", "p2", "p3", "p4"));

        // 첫 발언자는 패스 불가
        Decision first = state.topDecision();
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "BID_FACTION", first.getId(), Map.of("pass", true))));

        bid(state, "p1", 3);
        Decision second = state.topDecision();
        EngineException e = assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p2", "BID_FACTION", second.getId(), Map.of("bid", 3))));
        assertTrue(e.getMessage().contains("4 이상"));
    }

    @Test
    void 비딩값은_최종_점수에서_차감된다() {
        GameState base = newGame(data, 7L);
        completeSetup(engine, data, base);
        base.setRound(6);
        for (int i = 0; i < 4; i++) {
            engine.apply(base, new GameEngine.Submit(base.getActivePlayer(), "ACTION_PASS", null, Map.of()));
        }

        GameState bidded = newGame(data, 7L);
        completeSetup(engine, data, bidded);
        bidded.player("p1").setBidVp(3);
        bidded.setRound(6);
        for (int i = 0; i < 4; i++) {
            engine.apply(bidded, new GameEngine.Submit(bidded.getActivePlayer(), "ACTION_PASS", null, Map.of()));
        }

        assertEquals("FINISHED", bidded.getPhase());
        assertEquals(base.player("p1").getVp() - 3, bidded.player("p1").getVp());
    }
}
