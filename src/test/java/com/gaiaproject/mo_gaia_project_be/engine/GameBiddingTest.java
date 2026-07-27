package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // 후보는 인원수만큼만 랜덤 드로우, 후보 순서 = 턴 슬롯 사전 고정 (pool[0]=1턴 … pool[3]=4턴)
        List<String> pool = List.copyOf(state.getBoard().getFactionPool());
        assertEquals(4, pool.size());
        assertEquals(pool, state.getBoard().getBidSlotFactions());

        // 1차 경매: p2가 2로 낙찰 — 4턴 슬롯 종족(pool[3])을 선택 → p2는 4턴
        bid(state, "p1", 1);
        bid(state, "p2", 2);
        pass(state, "p3");
        pass(state, "p4");
        pass(state, "p1");
        assertEquals("CHOOSE_FACTION", state.topDecision().getType());
        assertEquals("p2", state.topDecision().getTarget());
        choose(state, "p2", pool.get(3));
        assertEquals(2, state.player("p2").getBidVp());
        assertFalse(state.getBoard().getFactionPool().contains(pool.get(3)));
        assertEquals("p2", state.getBoard().getBidTurnSlots().get("4"));

        // 2차 경매: 남은 [p1, p3, p4], p1부터 — p1이 1로 낙찰, 1턴 슬롯 종족 선택
        assertEquals("p1", state.topDecision().getTarget());
        bid(state, "p1", 1);
        pass(state, "p3");
        pass(state, "p4");
        choose(state, "p1", pool.get(0));

        // 3차 경매: [p3, p4] — p3이 1로 낙찰, 3턴 슬롯 종족 선택
        bid(state, "p3", 1);
        pass(state, "p4");
        choose(state, "p3", pool.get(2));

        // 마지막 p4는 경매 없이 비딩 0으로 남은 후보(2턴 슬롯) 선택
        assertEquals("CHOOSE_FACTION", state.topDecision().getType());
        assertEquals("p4", state.topDecision().getTarget());
        choose(state, "p4", pool.get(1));

        // 종족에 고정된 슬롯 순서 = 턴 순서 (낙찰 순서 아님), 초기 배치 페이즈로 전환
        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals(List.of("p1", "p4", "p3", "p2"), state.getTurnOrder());
        assertEquals("PLACE_INITIAL_MINE", state.topDecision().getType());

        // 초기 배치~부스터까지 정상 진행
        completeSetup(engine, data, state);
        assertEquals("PLAYING", state.getPhase());
    }

    @Test
    void 모드b는_낙찰자가_턴_순번을_직접_선택한다() {
        GameState state = GameSetup.createWithBidding(data, 3L, List.of("p1", "p2", "p3", "p4"), true);
        List<String> pool = List.copyOf(state.getBoard().getFactionPool());

        // 1차: p1 낙찰 → 3번 슬롯 선택
        bid(state, "p1", 1);
        pass(state, "p2");
        pass(state, "p3");
        pass(state, "p4");
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_FACTION", state.topDecision().getId(),
                Map.of("faction", pool.get(0), "turnSlot", 3)));

        // 2차: p2 낙찰 — 이미 선택된 슬롯은 거부, 1번 슬롯 선택
        bid(state, "p2", 1);
        pass(state, "p3");
        pass(state, "p4");
        Decision pick2 = state.topDecision();
        assertThrows(EngineException.class, () -> engine.apply(state, new GameEngine.Submit(
                "p2", "CHOOSE_FACTION", pick2.getId(), Map.of("faction", pool.get(1), "turnSlot", 3))));
        engine.apply(state, new GameEngine.Submit("p2", "CHOOSE_FACTION", pick2.getId(),
                Map.of("faction", pool.get(1), "turnSlot", 1)));

        // 3차: p3 낙찰 → 4번 슬롯
        bid(state, "p3", 1);
        pass(state, "p4");
        engine.apply(state, new GameEngine.Submit("p3", "CHOOSE_FACTION", state.topDecision().getId(),
                Map.of("faction", pool.get(2), "turnSlot", 4)));

        // 마지막 p4 → 남은 2번 슬롯
        engine.apply(state, new GameEngine.Submit("p4", "CHOOSE_FACTION", state.topDecision().getId(),
                Map.of("faction", pool.get(3), "turnSlot", 2)));

        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals(List.of("p2", "p4", "p1", "p3"), state.getTurnOrder()); // 슬롯 1~4 순
        assertEquals("p2", state.topDecision().getTarget());
    }

    @Test
    void 비딩_후보에는_같은_홈행성_종족이_중복되지_않는다() {
        for (long seed = 0; seed < 100; seed++) {
            GameState state = GameSetup.createWithBidding(data, seed, List.of("p1", "p2", "p3", "p4"));
            List<String> pool = state.getBoard().getFactionPool();
            assertEquals(4, pool.size());
            Set<String> planets = new HashSet<>();
            for (String id : pool) {
                assertTrue(planets.add(data.faction(id).get("homePlanet").asText()),
                        "시드 " + seed + ": 홈행성 중복 — " + pool);
            }
        }
    }

    @Test
    void 최소_비딩은_1이고_현재가_이하_비딩은_거부된다() {
        GameState state = GameSetup.createWithBidding(data, 3L, List.of("p1", "p2", "p3", "p4"));

        // 최소 제시가 1 — 0은 거부
        Decision first = state.topDecision();
        EngineException zero = assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "BID_FACTION", first.getId(), Map.of("bid", 0))));
        assertTrue(zero.getMessage().contains("1 이상"));

        bid(state, "p1", 3);
        Decision second = state.topDecision();
        EngineException e = assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p2", "BID_FACTION", second.getId(), Map.of("bid", 3))));
        assertTrue(e.getMessage().contains("4 이상"));
    }

    @Test
    void 첫_발언자도_패스할_수_있고_전원_패스하면_마지막_잔류자가_0으로_낙찰된다() {
        GameState state = GameSetup.createWithBidding(data, 3L, List.of("p1", "p2", "p3", "p4"));
        List<String> pool = List.copyOf(state.getBoard().getFactionPool());

        pass(state, "p1"); // 첫 발언자 패스 허용
        pass(state, "p2");
        pass(state, "p3");

        // 마지막 잔류자 p4가 비딩 0으로 낙찰
        assertEquals("CHOOSE_FACTION", state.topDecision().getType());
        assertEquals("p4", state.topDecision().getTarget());
        choose(state, "p4", pool.get(0));
        assertEquals(0, state.player("p4").getBidVp());
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
