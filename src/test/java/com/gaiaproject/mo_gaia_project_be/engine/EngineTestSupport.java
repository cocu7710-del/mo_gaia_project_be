package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.HexCoord;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.HexState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 엔진 테스트 공용 헬퍼 */
final class EngineTestSupport {

    private EngineTestSupport() {}

    static GameState newGame(GameData data, long seed) {
        return GameSetup.create(data, seed, List.of(
                new GameSetup.PlayerSeat("p1", "GEODENS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "TERRANS"),
                new GameSetup.PlayerSeat("p4", "NEVLAS")));
    }

    static void playAllInitialMines(GameEngine engine, GameData data, GameState state) {
        while ("SETUP_MINES".equals(state.getPhase())) {
            Decision top = state.topDecision();
            String player = top.getTarget();
            String home = data.faction(state.player(player).getFaction()).get("homePlanet").asText();
            HexCoord c = HexCoord.parse(findHex(state,
                    h -> home.equals(h.getPlanet()) && !h.hasBuilding()));
            engine.apply(state, new GameEngine.Submit(player, "PLACE_INITIAL_MINE", top.getId(),
                    Map.of("hexQ", c.q(), "hexR", c.r())));
        }
    }

    static void pickAllBoosters(GameEngine engine, GameState state) {
        while ("SETUP_BOOSTER".equals(state.getPhase())) {
            Decision top = state.topDecision();
            String booster = state.getBoard().getBoosterHolders().entrySet().stream()
                    .filter(e -> e.getValue() == null)
                    .map(Map.Entry::getKey)
                    .findFirst().orElseThrow();
            engine.apply(state, new GameEngine.Submit(top.getTarget(), "CHOOSE_BOOSTER", top.getId(),
                    Map.of("booster", booster)));
        }
    }

    /** 초기 광산 배치 + 부스터 선택 + 1라운드 수입 결정 해소까지 완료 → PLAYING */
    static void completeSetup(GameEngine engine, GameData data, GameState state) {
        playAllInitialMines(engine, data, state);
        pickAllBoosters(engine, state);
        resolveRoundStartDecisions(engine, state);
    }

    /** 라운드 시작의 수입 파워 순서 결정을 기본값으로 해소 (팅커로이드 타일 선택 등은 테스트가 직접 처리) */
    static void resolveRoundStartDecisions(GameEngine engine, GameState state) {
        while (!state.getDecisionStack().isEmpty()
                && "INCOME_POWER_ORDER".equals(state.topDecision().getType())) {
            Decision top = state.topDecision();
            engine.apply(state, new GameEngine.Submit(
                    top.getTarget(), "INCOME_POWER_ORDER", top.getId(), Map.of("order", "TOKENS_FIRST")));
        }
    }

    /** 함대 우주선 헥스 좌표 */
    static HexCoord shipCoord(GameState state, String ship) {
        return HexCoord.parse(state.getHexes().entrySet().stream()
                .filter(e -> ship.equals(e.getValue().getShip()))
                .map(Map.Entry::getKey).findFirst().orElseThrow());
    }

    /** 함대 입장 — 거리 QIC 자동 계산·충전 (game-spec 7.7-2) */
    static void enterFleet(GameEngine engine, GameState state, String playerId, String ship) {
        int qic = qicForRange(state, playerId, shipCoord(state, ship), 1);
        state.player(playerId).setQic(Math.max(state.player(playerId).getQic(), qic));
        engine.apply(state, new GameEngine.Submit(playerId, "ACTION_FLEET_ENTER", null,
                Map.of("ship", ship, "qicForRange", qic)));
    }

    /** 명시적 턴 종료 (자유 행동 구간 닫기 — game-spec §7.10) */
    static void endTurn(GameEngine engine, GameState state) {
        engine.apply(state, new GameEngine.Submit(state.getActivePlayer(), "END_TURN", null, Map.of()));
    }

    /** 스택 최상단이 리치인 동안 전부 거절 처리 */
    static void declineAllLeech(GameEngine engine, GameState state) {
        while (!state.getDecisionStack().isEmpty()
                && "LEECH_RESPONSE".equals(state.topDecision().getType())) {
            Decision top = state.topDecision();
            engine.apply(state, new GameEngine.Submit(top.getTarget(), "LEECH_RESPONSE", top.getId(),
                    Map.of("accept", false)));
        }
    }

    static String findHex(GameState state, Predicate<HexState> filter) {
        return state.getHexes().entrySet().stream()
                .filter(e -> filter.test(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("조건에 맞는 헥스 없음"));
    }

    /** 대상 좌표까지 플레이어 건물 최단 거리 기준으로 필요한 QIC 수 */
    static int qicForRange(GameState state, String playerId, HexCoord target, int baseRange) {
        int dist = state.getHexes().entrySet().stream()
                .filter(e -> playerId.equals(e.getValue().getBuildingOwner()))
                .mapToInt(e -> HexCoord.parse(e.getKey()).distance(target))
                .min().orElseThrow();
        return Math.max(0, (int) Math.ceil((dist - baseRange) / 2.0));
    }
}
