package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.HexCoord;
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
    void 인공물_7_8은_광산_개수_카운트에만_포함되고_수입과는_무관하다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        int oreBefore = engine.incomePreview(state).get("p1").get("ore");

        p1.getArtifacts().add("ARTIFACT_7"); // 소행성 가상 광산
        p1.getArtifacts().add("ARTIFACT_8"); // 초월 차원 가상 광산
        assertEquals(oreBefore, (int) engine.incomePreview(state).get("p1").get("ore")); // 수입 광산 수 미포함

        // 검은행성 광산도 광산 개수 카운트 포함 (E-3)
        String emptyKey = EngineTestSupport.findHex(state,
                h -> "EMPTY".equals(h.getPlanet()) && !h.hasBuilding() && h.getShip() == null);
        state.getHexes().get(emptyKey).setPlanet("BLACK_PLANET");
        state.getHexes().get(emptyKey).setBuildingOwner("p1");
        state.getHexes().get(emptyKey).setBuildingType("BLACK_PLANET_MINE");

        // 패스 "광산당 1VP"(BOOSTER_4)에는 포함: 실제 광산 2 + 가상 2 + 검은행성 1 = 5VP
        String old = p1.getBooster();
        String b4Holder = state.getBoard().getBoosterHolders().get("BOOSTER_4");
        if (b4Holder != null) {
            state.player(b4Holder).setBooster(old);
            state.getBoard().getBoosterHolders().put(old, b4Holder);
        } else {
            state.getBoard().getBoosterHolders().put(old, null);
        }
        state.getBoard().getBoosterHolders().put("BOOSTER_4", "p1");
        p1.setBooster("BOOSTER_4");

        int vpBefore = p1.getVp();
        String freeBooster = state.getBoard().getBoosterHolders().entrySet().stream()
                .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
        state.setActivePlayer("p1");
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_PASS", null, Map.of("booster", freeBooster)));
        assertEquals(vpBefore + 5, p1.getVp());
    }

    @Test
    void 인공물_7_획득은_광산_건설_라운드_점수를_받는다() {
        GameState state = readyGame();
        state.getBoard().getRoundScoringTiles().set(0, "ROUND_TILE_MINE"); // 1라운드 = 광산 건설 2VP
        state.getBoard().getArtifactOffers().put("ARTIFACT_7", null);
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_ARTIFACT", "p1", Map.of()));

        PlayerState p1 = state.player("p1");
        int roundVpBefore = p1.getVpBreakdown().getOrDefault("ROUND_1", 0);
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_ARTIFACT", state.topDecision().getId(),
                Map.of("artifact", "ARTIFACT_7")));
        assertEquals(roundVpBefore + 2, (int) p1.getVpBreakdown().getOrDefault("ROUND_1", 0));
    }

    @Test
    void 연방_토큰이_없으면_인공물_13을_획득할_수_없다() {
        GameState state = readyGame();
        state.getBoard().getArtifactOffers().put("ARTIFACT_13", null);
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_ARTIFACT", "p1", Map.of()));
        Decision d = state.topDecision();
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "CHOOSE_ARTIFACT", d.getId(), Map.of("artifact", "ARTIFACT_13"))));

        // 토큰 보유 시 획득 가능 → 재수령 결정 연쇄
        state.player("p1").getFederationTokens().add("FED_TILE_2");
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_ARTIFACT", state.topDecision().getId(),
                Map.of("artifact", "ARTIFACT_13")));
        assertEquals("CHOOSE_FED_TOKEN_REUSE", state.topDecision().getType());
    }

    @Test
    void 확장_기술_타일은_연결된_함대에_입장해야_획득한다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        String ship = state.getBoard().getExpansionTechShips().get("EXPANSION_1");

        // 미입장 상태 → 거부
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        Decision d1 = state.topDecision();
        EngineException e = assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", d1.getId(), Map.of("position", "EXPANSION_1"))));
        assertTrue(e.getMessage().contains(ship));

        // 연결된 함대 입장 후 → 획득 가능
        EngineTestSupport.enterFleet(engine, state, "p1", ship);
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", "EXPANSION_1")));
        assertTrue(p1.getTechTiles().contains(state.getBoard().getTechOffers().get("EXPANSION_1")));
    }

    @Test
    void 타클론은_브레인스톤을_가이아로_보낼_수_없으면_함대_입장이_거부된다() {
        GameState state = GameSetup.create(data, 3L, List.of(
                new GameSetup.PlayerSeat("p1", "TAKLONS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
        completeSetup(engine, data, state);
        PlayerState p1 = state.player("p1");

        p1.setBrainstone(null); // 영구 제거 상태 — 가이아 구역으로 보낼 브레인스톤이 없음
        assertThrows(EngineException.class,
                () -> EngineTestSupport.enterFleet(engine, state, "p1", "TF_MARS"));

        p1.setBrainstone("BOWL2");
        EngineTestSupport.enterFleet(engine, state, "p1", "TF_MARS");
        assertEquals("GAIA", p1.getBrainstone()); // 입장 시 가이아 구역으로 이동
    }

    @Test
    void 함대_입장은_플레이어당_최대_3회다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.setVp(100);
        p1.getVpBreakdown().merge("GAIN", 90, Integer::sum);
        for (String ship : List.of("TF_MARS", "ECLIPSE", "REBELLION")) {
            EngineTestSupport.enterFleet(engine, state, "p1", ship);
            state.setTurnEndPending(false); // 테스트 편의: 자유 행동 구간 종료 처리
        }
        EngineException e = assertThrows(EngineException.class,
                () -> EngineTestSupport.enterFleet(engine, state, "p1", "TWILIGHT"));
        assertTrue(e.getMessage().contains("최대 3회"));
    }

    @Test
    void 리벨리온_3QIC_액션은_기술_타일_선택을_연다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        EngineTestSupport.enterFleet(engine, state, "p1", "REBELLION");
        state.setTurnEndPending(false);
        p1.setQic(10);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null,
                Map.of("actionId", "REBELLION_TECH")));
        assertEquals("CHOOSE_TECH_TILE", state.topDecision().getType());

        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", "TERRA_FORMING")));
        assertEquals(1, p1.getTechTiles().size());
    }

    @Test
    void 확장_기본_타일3은_광산_비용까지_무료로_건설한다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");

        // BASIC_EXP_TILE_3가 놓인 확장 슬롯과 연결 함대 찾기 → 입장
        String position = state.getBoard().getTechOffers().entrySet().stream()
                .filter(e -> "BASIC_EXP_TILE_3".equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        EngineTestSupport.enterFleet(engine, state, "p1",
                state.getBoard().getExpansionTechShips().get(position));
        state.setTurnEndPending(false);

        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", position)));
        assertEquals("PLACE_MINE", state.topDecision().getType()); // 무료 2삽 + 광산 건설 결정

        // 2삽 이내 링 행성에 건설 — 크레딧·광석 소모 없음 (거리 QIC만)
        HexCoord target = HexCoord.parse(EngineTestSupport.findHex(state, h -> !h.hasBuilding()
                && ("VOLCANIC".equals(h.getPlanet()) || "DESERT".equals(h.getPlanet())
                    || "TERRA".equals(h.getPlanet()) || "SWAMP".equals(h.getPlanet()))));
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        p1.setQic(Math.max(p1.getQic(), qic));
        int creditsBefore = p1.getCredits();
        int oreBefore = p1.getOre();
        engine.apply(state, new GameEngine.Submit("p1", "PLACE_MINE", state.topDecision().getId(),
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));
        assertEquals(creditsBefore, p1.getCredits());
        assertEquals(oreBefore, p1.getOre());
    }

    @Test
    void 함대_입장은_VP_5를_소모하고_입장_순서_보너스를_준다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");

        EngineTestSupport.enterFleet(engine, state, "p1", "TF_MARS");
        assertEquals(5, p1.getVp());                        // 10 - 5, 1번째 입장 보너스 없음
        assertTrue(p1.getFleetProbes().contains("TF_MARS"));
        EngineTestSupport.endTurn(engine, state);           // 자유 행동 구간 닫기 → p2 턴

        // 2번째 입장자(p2)는 파워 차징 2
        PlayerState p2 = state.player("p2");
        int bowl2Before = p2.getBowl2();
        EngineTestSupport.enterFleet(engine, state, "p2", "TF_MARS");
        assertEquals(5, p2.getVp());
        assertEquals(bowl2Before + 2, p2.getBowl2());       // 2/4/0 → 차징 2 → 0/6/0

        // 중복 입장 거부
        state.setTurnEndPending(false); // 자유 행동 구간 종료 처리 (테스트 편의)
        state.setActivePlayer("p1");
        assertThrows(EngineException.class, () -> EngineTestSupport.enterFleet(engine, state, "p1", "TF_MARS"));
    }

    @Test
    void 함대_입장도_항해_거리_규칙을_따른다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        HexCoord ship = EngineTestSupport.shipCoord(state, "TF_MARS");
        int needed = EngineTestSupport.qicForRange(state, "p1", ship, 1);
        assertTrue(needed > 0, "시드 3 맵에서 함대는 초기 사거리 밖이어야 함 (거리 규칙 검증 전제)");

        // QIC 미지불 → 거리 밖 거부
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_FLEET_ENTER", null,
                        Map.of("ship", "TF_MARS", "qicForRange", 0))));

        // 초과분 QIC 지불 → 입장 성공 + QIC 차감
        p1.setQic(needed);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET_ENTER", null,
                Map.of("ship", "TF_MARS", "qicForRange", needed)));
        assertEquals(0, p1.getQic());
        assertTrue(p1.getFleetProbes().contains("TF_MARS"));
    }

    @Test
    void 네블라는_입장_시_토큰_1개를_영구_소각한다() {
        GameState state = readyGame();
        PlayerState p4 = state.player("p4"); // NEVLAS
        state.setActivePlayer("p4");
        int tokensBefore = p4.getBowl1() + p4.getBowl2() + p4.getBowl3();

        EngineTestSupport.enterFleet(engine, state, "p4", "ECLIPSE");

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

        EngineTestSupport.enterFleet(engine, state, "p1", "TF_MARS");
        state.setTurnEndPending(false); // 자유 행동 구간 종료 처리 (테스트 편의)
        state.setActivePlayer("p1");
        p1.setQic(10); // 입장 거리 QIC 소모분 재충전 (테스트 편의)

        int vpBefore = p1.getVp();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null, Map.of("actionId", "TF_MARS_VP")));
        assertEquals(vpBefore + 2, p1.getVp());             // 기술 타일 0개 → 2VP
        assertEquals(8, p1.getQic());                       // QIC 2 소모

        // 같은 라운드 재사용 거부 (다른 입장자라도)
        PlayerState p2 = state.player("p2");
        p2.getFleetProbes().add("TF_MARS");
        p2.setQic(10);
        state.setTurnEndPending(false); // 자유 행동 구간 종료 처리 (테스트 편의)
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
    void 이클립스_6크레딧_액션은_가이아포머_없이_소행성에_무료_광산을_짓는다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getFleetProbes().add("ECLIPSE");
        p1.getStock().put("GAIAFORMER", 0); // 포머 재고 0 — 함대 액션 경로는 소각 불필요해야 함
        p1.setCredits(10);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_FLEET", null, Map.of("actionId", "ECLIPSE_MINE")));
        assertEquals(4, p1.getCredits());                   // 액션 비용 6크레딧만 소모
        assertEquals("PLACE_MINE", state.topDecision().getType());

        String key = EngineTestSupport.findHex(state,
                h -> "ASTEROIDS".equals(h.getPlanet()) && !h.hasBuilding());
        HexCoord target = HexCoord.parse(key);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        p1.setQic(Math.max(p1.getQic(), qic));
        int oreBefore = p1.getOre();
        engine.apply(state, new GameEngine.Submit("p1", "PLACE_MINE", state.topDecision().getId(),
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("MINE", state.getHexes().get(key).getBuildingType());
        assertEquals("p1", state.getHexes().get(key).getBuildingOwner());
        assertEquals(4, p1.getCredits());                   // 건설 자체는 무료
        assertEquals(oreBefore, p1.getOre());
        assertEquals(0, p1.getStock().get("GAIAFORMER"));   // 포머 소각 없음
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
        state.setTurnEndPending(false); // 자유 행동 구간 종료 처리 (테스트 편의)
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
