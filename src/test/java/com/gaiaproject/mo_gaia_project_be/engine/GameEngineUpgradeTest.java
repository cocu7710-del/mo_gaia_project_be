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
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.declineAllLeech;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.findHex;
import static com.gaiaproject.mo_gaia_project_be.engine.EngineTestSupport.newGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 결정 연쇄 A3: 업그레이드 → 리치 → 기술 타일 → 트랙 전진 → (4→5) 검은행성 */
class GameEngineUpgradeTest {

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
        PlayerState p1 = state.player("p1");
        p1.setCredits(100);
        p1.setOre(100);
        p1.setQic(100);
        return state;
    }

    private void upgrade(GameState state, String hexKey, String to) {
        HexCoord c = HexCoord.parse(hexKey);
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_UPGRADE", null,
                Map.of("hexQ", c.q(), "hexR", c.r(), "to", to)));
    }

    @Test
    void 광산을_교역소로_업그레이드하면_재고가_교환된다() {
        GameState state = readyGame();
        String mineKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));

        upgrade(state, mineKey, "TRADING_STATION");

        assertEquals("TRADING_STATION", state.getHexes().get(mineKey).getBuildingType());
        assertEquals(7, state.player("p1").stockOf("MINE"));            // 6 + 반환 1
        assertEquals(3, state.player("p1").stockOf("TRADING_STATION")); // 4 - 1

        declineAllLeech(engine, state);
        assertEquals("p1", state.getActivePlayer()); // 연쇄 해소 후에도 자유 행동 구간 (§7.10)
        EngineTestSupport.endTurn(engine, state);
        assertEquals("p2", state.getActivePlayer()); // 턴 종료 → 턴 이동
    }

    @Test
    void 연구소_업그레이드는_기술_타일_선택을_연쇄시킨다() {
        GameState state = readyGame();
        String key = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));

        upgrade(state, key, "TRADING_STATION");
        declineAllLeech(engine, state);
        EngineTestSupport.endTurn(engine, state);
        state.setActivePlayer("p1"); // 테스트 편의: 턴을 되돌림

        upgrade(state, key, "RESEARCH_LAB");
        declineAllLeech(engine, state); // 리치가 먼저 해소되고

        Decision top = state.topDecision(); // 그다음 기술 타일 선택이 열린다
        assertEquals("CHOOSE_TECH_TILE", top.getType());
        assertEquals("p1", top.getTarget());

        String aiTile = state.getBoard().getTechOffers().get("AI");
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", top.getId(),
                Map.of("position", "AI")));

        PlayerState p1 = state.player("p1");
        assertTrue(p1.getTechTiles().contains(aiTile));
        assertEquals(1, p1.track("AI"));            // 트랙 슬롯 타일 → 해당 트랙 무료 전진
        assertTrue(state.getDecisionStack().isEmpty());
        EngineTestSupport.endTurn(engine, state);
        assertEquals("p2", state.getActivePlayer());
    }

    @Test
    void 최종_순위_동률은_비딩값이_적은_쪽이_이긴다() {
        GameState state = new GameState();
        PlayerState a = new PlayerState();
        a.setVp(80);
        a.setBidVp(5);
        PlayerState b = new PlayerState();
        b.setVp(80);
        b.setBidVp(2); // 동률 — 비딩 적은 b가 승리
        PlayerState c = new PlayerState();
        c.setVp(90);
        PlayerState d = new PlayerState();
        d.setVp(80);
        d.setBidVp(2); // b와 총점·비딩 모두 동일 → 공동 순위
        state.getPlayers().put("a", a);
        state.getPlayers().put("b", b);
        state.getPlayers().put("c", c);
        state.getPlayers().put("d", d);

        GameEngine.assignFinalRanks(state);

        assertEquals(1, c.getFinalRank());
        assertEquals(2, b.getFinalRank());
        assertEquals(2, d.getFinalRank()); // 공동 2위
        assertEquals(4, a.getFinalRank());
    }

    @Test
    void 최종_점수_미리보기는_구성_요소_합과_일치한다() {
        GameState state = readyGame();
        state.player("p1").setBidVp(2);
        var preview = engine.finalScorePreview(state);
        for (String pid : state.getPlayers().keySet()) {
            var v = preview.get(pid);
            int expected = state.player(pid).getVp() + v.get("rank") + v.get("track")
                    + v.get("resources") - v.get("bid");
            assertEquals(expected, (int) v.get("projectedTotal"));
        }
        assertEquals(2, (int) preview.get("p1").get("bid"));
    }

    @Test
    void 새_행성_종류_개척은_라운드_점수와_기오덴_PI_지식을_발동한다() {
        GameState state = readyGame(); // p1 = 기오덴 (홈 OXIDE 광산 2)
        PlayerState p1 = state.player("p1");
        // 기오덴 PI 배치 (이미 개척한 OXIDE 위 — 행성 종류 변화 없음)
        String piKey = findHex(state, h -> "OXIDE".equals(h.getPlanet()) && !h.hasBuilding());
        state.getHexes().get(piKey).setBuildingOwner("p1");
        state.getHexes().get(piKey).setBuildingType("PLANETARY_INSTITUTE");
        state.getBoard().getRoundScoringTiles().set(0, "ROUND_TILE_NEW_PLANET_TYPE"); // 새 행성 종류 3VP

        String targetKey = findHex(state, h -> !h.hasBuilding() && !"EMPTY".equals(h.getPlanet())
                && !"OXIDE".equals(h.getPlanet()) && !"GAIA".equals(h.getPlanet())
                && !"TRANSDIM".equals(h.getPlanet()) && !"ASTEROIDS".equals(h.getPlanet())
                && !"TRANSCENDENT".equals(h.getPlanet()));
        HexCoord target = HexCoord.parse(targetKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);
        int kBefore = p1.getKnowledge();
        int roundBefore = p1.getVpBreakdown().getOrDefault("ROUND_1", 0);

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals(kBefore + 3, p1.getKnowledge()); // 기오덴 PI: 새 행성 타입 +3지식
        assertEquals(roundBefore + 3, (int) p1.getVpBreakdown().getOrDefault("ROUND_1", 0)); // 라운드 3VP
    }

    @Test
    void 리치는_블록_내에서_순서_무관하게_동시_응답할_수_있다() {
        GameState state = readyGame();
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "LEECH_RESPONSE", "p2",
                Map.of("from", "p1", "amount", 2, "vpCost", 1)));
        Decision firstPushed = state.topDecision();
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "LEECH_RESPONSE", "p3",
                Map.of("from", "p1", "amount", 2, "vpCost", 1)));

        // 최상단이 아닌(먼저 push된) p2 결정에 먼저 응답 가능
        engine.apply(state, new GameEngine.Submit("p2", "LEECH_RESPONSE", firstPushed.getId(),
                Map.of("accept", false)));
        engine.apply(state, new GameEngine.Submit("p3", "LEECH_RESPONSE", state.topDecision().getId(),
                Map.of("accept", true)));
        assertTrue(state.getDecisionStack().isEmpty());
    }

    @Test
    void 고급_타일_트랙_슬롯은_해당_트랙_4레벨이_필요하다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getTechTiles().add("BASIC_TILE_5");
        p1.getFederationTokens().add("FED_TILE_2");

        // AI 레벨 4 미만 → 거부
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        Decision d1 = state.topDecision();
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", d1.getId(),
                        Map.of("position", "AI", "advanced", true, "coveredTile", "BASIC_TILE_5"))));

        // AI 레벨 4 → 획득: 연방 토큰 뒤집기 + 기본 타일 덮기 + 선점 기록
        p1.getTracks().put("AI", 4);
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", "AI", "advanced", true, "coveredTile", "BASIC_TILE_5")));

        String advId = state.getBoard().getAdvTechOffers().get("AI");
        assertTrue(p1.getTechTiles().contains(advId));
        assertTrue(p1.getCoveredTechTiles().contains("BASIC_TILE_5"));
        assertEquals(1, p1.getUsedFederationTokens().size());
        assertEquals("p1", state.getBoard().getAdvTechTakenBy().get(advId));
    }

    @Test
    void 공용_고급_타일은_트랙과_무관하게_공용_조건만_충족하면_된다() {
        GameState state = readyGame();
        state.getBoard().setCommonAdvCondition("VP_25");
        PlayerState p1 = state.player("p1");
        p1.getTechTiles().add("BASIC_TILE_5");
        p1.getFederationTokens().add("FED_TILE_2");

        // VP 25 미만 → 거부
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        Decision d1 = state.topDecision();
        assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", d1.getId(),
                        Map.of("position", "COMMON", "advanced", true, "coveredTile", "BASIC_TILE_5"))));

        // VP 25 이상이면 전 트랙 저레벨이어도 획득 가능
        p1.setVp(25);
        p1.getVpBreakdown().merge("GAIN", 15, Integer::sum); // vp = breakdown 합 불변식 유지
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "CHOOSE_TECH_TILE", "p1", Map.of()));
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", state.topDecision().getId(),
                Map.of("position", "COMMON", "advanced", true, "coveredTile", "BASIC_TILE_5")));

        String advId = state.getBoard().getAdvTechOffers().get("COMMON");
        assertTrue(p1.getTechTiles().contains(advId));
        assertEquals("p1", state.getBoard().getAdvTechTakenBy().get(advId));
    }

    @Test
    void 패시브_고급_타일은_교역소_건설과_연구_전진마다_VP를_준다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getTechTiles().add("ADV_TILE_17"); // 교역소 건설마다 +3VP
        p1.getTechTiles().add("ADV_TILE_18"); // 연구 전진마다 +2VP
        p1.setKnowledge(20);

        String mineKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        upgrade(state, mineKey, "TRADING_STATION");
        assertEquals(3, p1.getVpBreakdown().getOrDefault("TECH_ADV", 0));

        declineAllLeech(engine, state);
        EngineTestSupport.endTurn(engine, state);
        state.setActivePlayer("p1");

        engine.apply(state, new GameEngine.Submit("p1", "ACTION_RESEARCH", null, Map.of("track", "AI")));
        assertEquals(5, p1.getVpBreakdown().getOrDefault("TECH_ADV", 0)); // +2

        // 덮인 타일은 효과 상실
        p1.getCoveredTechTiles().add("ADV_TILE_18");
        EngineTestSupport.endTurn(engine, state);
        state.setActivePlayer("p1");
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_RESEARCH", null, Map.of("track", "AI")));
        assertEquals(5, p1.getVpBreakdown().getOrDefault("TECH_ADV", 0)); // 변화 없음
    }

    @Test
    void 매드_안드로이드는_업그레이드_경로가_스왑된다() {
        GameState state = GameSetup.create(data, 3L, List.of(
                new GameSetup.PlayerSeat("p1", "BESCODS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
        completeSetup(engine, data, state);
        PlayerState p1 = state.player("p1");
        p1.setCredits(100);
        p1.setOre(100);

        String key = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        upgrade(state, key, "TRADING_STATION");
        declineAllLeech(engine, state);
        EngineTestSupport.endTurn(engine, state);
        state.setActivePlayer("p1");

        // 교역소 → 의회 불가 (스왑: 의회는 연구소에서)
        EngineException e = assertThrows(EngineException.class, () -> upgrade(state, key, "PLANETARY_INSTITUTE"));
        assertTrue(e.getMessage().contains("업그레이드 불가"));

        // 교역소 → 연구소는 가능 (기술 타일 연쇄 포함)
        upgrade(state, key, "RESEARCH_LAB");
        declineAllLeech(engine, state);
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE",
                state.topDecision().getId(), Map.of("position", "AI")));
        EngineTestSupport.endTurn(engine, state);
        state.setActivePlayer("p1");

        // 연구소 → 아카데미 불가 (스왑: 아카데미는 교역소에서), 연구소 → 의회 가능
        EngineException e2 = assertThrows(EngineException.class, () -> upgrade(state, key, "ACADEMY"));
        assertTrue(e2.getMessage().contains("업그레이드 불가"));
        upgrade(state, key, "PLANETARY_INSTITUTE");
        assertEquals("PLANETARY_INSTITUTE", state.getHexes().get(key).getBuildingType());
    }

    @Test
    void 항해_4에서_타일_전진하면_연방토큰_플립_후_검은행성이_연쇄된다() {
        GameState state = readyGame();
        PlayerState p1 = state.player("p1");
        p1.getTracks().put("NAVIGATION", 4);
        p1.getFederationTokens().add("FED_TILE_2");

        String key = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        upgrade(state, key, "TRADING_STATION");
        declineAllLeech(engine, state);
        EngineTestSupport.endTurn(engine, state);
        state.setActivePlayer("p1");
        upgrade(state, key, "RESEARCH_LAB");
        declineAllLeech(engine, state);

        Decision techDecision = state.topDecision();
        engine.apply(state, new GameEngine.Submit("p1", "CHOOSE_TECH_TILE", techDecision.getId(),
                Map.of("position", "NAVIGATION")));

        // 4→5: 토큰 플립 + 점유 + 검은행성 배치 결정
        assertEquals(5, p1.track("NAVIGATION"));
        assertTrue(p1.getUsedFederationTokens().contains("FED_TILE_2"));
        assertEquals("p1", state.getBoard().getTrackLevel5Occupied().get("NAVIGATION"));

        Decision blackPlanet = state.topDecision();
        assertEquals("PLACE_BLACK_PLANET", blackPlanet.getType());

        String emptyKey = findHex(state, h -> "EMPTY".equals(h.getPlanet()) && !h.hasBuilding() && h.getShip() == null);
        HexCoord target = HexCoord.parse(emptyKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 4); // 항해 5 = 거리 4
        engine.apply(state, new GameEngine.Submit("p1", "PLACE_BLACK_PLANET", blackPlanet.getId(),
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals("BLACK_PLANET", state.getHexes().get(emptyKey).getPlanet());
        assertEquals("BLACK_PLANET_MINE", state.getHexes().get(emptyKey).getBuildingType());

        declineAllLeech(engine, state);
        assertTrue(state.getDecisionStack().isEmpty());
        EngineTestSupport.endTurn(engine, state);
        assertEquals("p2", state.getActivePlayer());
    }

    @Test
    void 라운드_점수_타일이_광산_건설에_적용된다() {
        GameState state = readyGame();
        state.getBoard().getRoundScoringTiles().set(0, "ROUND_TILE_MINE"); // 1라운드 = 광산 2VP

        PlayerState p1 = state.player("p1");
        String targetKey = findHex(state, h ->
                !h.hasBuilding() && !"EMPTY".equals(h.getPlanet()) && !"TRANSDIM".equals(h.getPlanet())
                        && !"GAIA".equals(h.getPlanet()) && !"ASTEROIDS".equals(h.getPlanet()));
        HexCoord target = HexCoord.parse(targetKey);
        int qic = EngineTestSupport.qicForRange(state, "p1", target, 1);

        int vpBefore = p1.getVp();
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_BUILD_MINE", null,
                Map.of("hexQ", target.q(), "hexR", target.r(), "qicForRange", qic)));

        assertEquals(vpBefore + 2, p1.getVp());
    }

    @Test
    void 같은_종류의_아카데미는_두_번_지을_수_없다() {
        GameState state = readyGame();

        // 지식 아카데미를 이미 보유한 상태로 세팅 (테스트 편의상 직접 배치)
        String existingKey = findHex(state, h -> !h.hasBuilding() && h.getShip() == null);
        HexState academyHex = state.getHexes().get(existingKey);
        academyHex.setBuildingOwner("p1");
        academyHex.setBuildingType("ACADEMY");
        academyHex.setAcademyType("KNOWLEDGE");

        String labKey = findHex(state, h -> "p1".equals(h.getBuildingOwner()) && "MINE".equals(h.getBuildingType()));
        state.getHexes().get(labKey).setBuildingType("RESEARCH_LAB");
        HexCoord lab = HexCoord.parse(labKey);

        EngineException e = assertThrows(EngineException.class, () -> engine.apply(state,
                new GameEngine.Submit("p1", "ACTION_UPGRADE", null,
                        Map.of("hexQ", lab.q(), "hexR", lab.r(), "to", "ACADEMY", "academyType", "KNOWLEDGE"))));
        assertTrue(e.getMessage().contains("아카데미"));
        assertEquals("RESEARCH_LAB", state.getHexes().get(labKey).getBuildingType()); // 거부됐으니 그대로

        // 다른 종류(QIC)는 가능
        engine.apply(state, new GameEngine.Submit("p1", "ACTION_UPGRADE", null,
                Map.of("hexQ", lab.q(), "hexR", lab.r(), "to", "ACADEMY", "academyType", "QIC")));
        assertEquals("ACADEMY", state.getHexes().get(labKey).getBuildingType());
        assertEquals("QIC", state.getHexes().get(labKey).getAcademyType());
    }
}
