package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 영속 계층 통합 테스트 — 실제 PostgreSQL(Testcontainers)에서
 * 게임 생성 → 셋업 진행 → 버전 검증 → 패스 → 언두까지 전체 수직 흐름 검증.
 * Docker 미가동 시 자동 스킵.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GameFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    GameService service;

    @Autowired
    GameData data;

    @Autowired
    GamePlayerRepository players;

    @Autowired
    ChatService chatService;

    @Autowired
    UserRepository users;

    @Autowired
    GameRepository games;

    @Test
    void 게임_생성부터_언두까지_영속_흐름이_동작한다() {
        GameService.CreatedGame created = service.createGame("통합테스트", 3L, List.of(
                new GameService.SeatRequest("alice", "GEODENS"),
                new GameService.SeatRequest("bob", "GLEENS"),
                new GameService.SeatRequest("carol", "TERRANS"),
                new GameService.SeatRequest("dave", "NEVLAS")));
        UUID gameId = created.gameId();

        // 초기 배치: 매 제출마다 스냅샷에서 상태를 다시 로드 (직렬화 왕복 검증)
        GameState state = service.loadLatestState(gameId);
        assertEquals("SETUP_MINES", state.getPhase());
        while ("SETUP_MINES".equals(state.getPhase())) {
            Decision top = state.topDecision();
            String player = top.getTarget();
            String home = data.faction(state.player(player).getFaction()).get("homePlanet").asText();
            String hexKey = state.getHexes().entrySet().stream()
                    .filter(e -> home.equals(e.getValue().getPlanet()) && !e.getValue().hasBuilding())
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            int comma = hexKey.indexOf(',');
            service.submit(gameId, new GameEngine.Submit(player, "PLACE_INITIAL_MINE", top.getId(),
                    Map.of("hexQ", Integer.parseInt(hexKey.substring(0, comma)),
                            "hexR", Integer.parseInt(hexKey.substring(comma + 1)))), null);
            state = service.loadLatestState(gameId);
        }

        // 부스터 선택
        while ("SETUP_BOOSTER".equals(state.getPhase())) {
            Decision top = state.topDecision();
            String booster = state.getBoard().getBoosterHolders().entrySet().stream()
                    .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
            service.submit(gameId, new GameEngine.Submit(top.getTarget(), "CHOOSE_BOOSTER", top.getId(),
                    Map.of("booster", booster)), null);
            state = service.loadLatestState(gameId);
        }
        assertEquals("PLAYING", state.getPhase());
        long versionAfterSetup = state.getVersion();
        assertTrue(versionAfterSetup >= 13); // GAME_CREATED 1 + 배치 8 + 부스터 4

        // 버전 충돌 감지
        String p1 = state.getActivePlayer();
        assertThrows(GameService.VersionConflictException.class, () -> service.submit(gameId,
                new GameEngine.Submit(p1, "ACTION_PASS", null, Map.of()), 1L));

        // 자유 변환 → 언두: FREE_ACTION_CONVERTED가 개별 롤백 대상이어야 한다 (이전 액션으로 건너뛰면 안 됨)
        int oreBefore = state.player(p1).getOre();
        service.submit(gameId, new GameEngine.Submit(p1, "ACTION_FREE", null,
                Map.of("conversions", List.of(Map.of("conversion", "ORE_CREDIT")))), null);
        assertEquals(oreBefore - 1, service.loadLatestState(gameId).player(p1).getOre());
        service.undoLastAction(gameId, p1);
        GameState afterFreeUndo = service.loadLatestState(gameId);
        assertEquals(oreBefore, afterFreeUndo.player(p1).getOre()); // 변환만 되돌아감
        assertEquals(p1, afterFreeUndo.getActivePlayer());
        long versionAfterFreeUndo = afterFreeUndo.getVersion();

        // 패스 → 언두
        String freeBooster = state.getBoard().getBoosterHolders().entrySet().stream()
                .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
        GameService.SubmitResult passResult = service.submit(gameId,
                new GameEngine.Submit(p1, "ACTION_PASS", null, Map.of("booster", freeBooster)), versionAfterFreeUndo);
        assertTrue(service.loadLatestState(gameId).player(p1).isPassed());

        GameService.SubmitResult undoResult = service.undoLastAction(gameId, p1);
        GameState restored = service.loadLatestState(gameId);
        assertFalse(restored.player(p1).isPassed());              // 패스 이전으로 복원
        assertTrue(undoResult.version() > passResult.version());  // append-only: 언두도 새 이벤트
        assertEquals("TURN_UNDONE", undoResult.events().get(0).type());
        assertEquals(p1, restored.getActivePlayer());
    }

    @Test
    void CONSENT_모드_상대가_행동한_뒤_언두는_동의_후_적용된다() {
        GameService.CreatedGame created = service.createGame("동의언두", 3L, List.of(
                new GameService.SeatRequest("cu1", "GEODENS"),
                new GameService.SeatRequest("cu2", "GLEENS"),
                new GameService.SeatRequest("cu3", "TERRANS"),
                new GameService.SeatRequest("cu4", "NEVLAS")),
                new GameService.GameOptions(false, "CONSENT"));
        UUID gameId = created.gameId();
        String p1 = created.playersByNickname().get("cu1").toString();
        String p2 = created.playersByNickname().get("cu2").toString();

        driveToPlaying(gameId);
        GameState state = service.loadLatestState(gameId);
        assertEquals("PLAYING", state.getPhase());

        // p1 패스 → p2 패스 (상대가 p1 액션 뒤에 행동)
        String b1 = freeBooster(state);
        service.submit(gameId, new GameEngine.Submit(p1, "ACTION_PASS", null, Map.of("booster", b1)), null);
        state = service.loadLatestState(gameId);
        service.submit(gameId, new GameEngine.Submit(p2, "ACTION_PASS", null, Map.of("booster", freeBooster(state))), null);

        // p1 언두 요청 — 즉시 롤백되지 않고 동의 대기 상태가 된다
        GameService.SubmitResult req = service.undoLastAction(gameId, p1);
        assertEquals("UNDO_REQUESTED", req.events().get(0).type());
        assertTrue(service.loadLatestStateJson(gameId).contains("undoRequest"));
        assertTrue(service.loadLatestState(gameId).player(p1).isPassed()); // 아직 롤백 안 됨

        // 승인 대상이 아닌 p1이 응답 시도 → 거부
        assertThrows(IllegalStateException.class, () -> service.respondUndo(gameId, p1, true));

        // p2 승인 → 롤백 실행
        GameService.SubmitResult done = service.respondUndo(gameId, p2, true);
        assertEquals("TURN_UNDONE", done.events().get(0).type());
        GameState restored = service.loadLatestState(gameId);
        assertFalse(restored.player(p1).isPassed());
        assertFalse(service.loadLatestStateJson(gameId).contains("undoRequest")); // 요청 정리됨
    }

    @Test
    void CONSENT_모드_거부하면_언두_요청이_취소된다() {
        GameService.CreatedGame created = service.createGame("거부언두", 3L, List.of(
                new GameService.SeatRequest("rj1", "GEODENS"),
                new GameService.SeatRequest("rj2", "GLEENS"),
                new GameService.SeatRequest("rj3", "TERRANS"),
                new GameService.SeatRequest("rj4", "NEVLAS")),
                new GameService.GameOptions(false, "CONSENT"));
        UUID gameId = created.gameId();
        String p1 = created.playersByNickname().get("rj1").toString();
        String p2 = created.playersByNickname().get("rj2").toString();

        driveToPlaying(gameId);
        GameState state = service.loadLatestState(gameId);
        service.submit(gameId, new GameEngine.Submit(p1, "ACTION_PASS", null, Map.of("booster", freeBooster(state))), null);
        state = service.loadLatestState(gameId);
        service.submit(gameId, new GameEngine.Submit(p2, "ACTION_PASS", null, Map.of("booster", freeBooster(state))), null);

        service.undoLastAction(gameId, p1);
        GameService.SubmitResult rejected = service.respondUndo(gameId, p2, false);
        assertEquals("UNDO_REJECTED", rejected.events().get(0).type());
        assertTrue(service.loadLatestState(gameId).player(p1).isPassed()); // 롤백 안 됨
        assertFalse(service.loadLatestStateJson(gameId).contains("undoRequest"));
    }

    @Test
    void 리플레이는_완료된_게임에서만_체크포인트와_시점별_상태를_제공한다() {
        GameService.CreatedGame created = service.createGame("리플레이", 3L, List.of(
                new GameService.SeatRequest("rp1", "GEODENS"),
                new GameService.SeatRequest("rp2", "GLEENS"),
                new GameService.SeatRequest("rp3", "TERRANS"),
                new GameService.SeatRequest("rp4", "NEVLAS")));
        UUID gameId = created.gameId();
        String p1 = created.playersByNickname().get("rp1").toString();

        // 진행 중엔 리플레이 불가
        assertThrows(IllegalStateException.class, () -> service.loadReplayCheckpoints(gameId));

        driveToPlaying(gameId); // 초기 배치+부스터 선택 — 체크포인트 여러 개 생성
        GameState state = service.loadLatestState(gameId);
        service.submit(gameId, new GameEngine.Submit(p1, "ACTION_PASS", null, Map.of("booster", freeBooster(state))), null);

        // 완료 표시 (실제 6라운드 진행은 다른 테스트에서 이미 검증됨 — 여기선 리플레이 조회 자체만 검증)
        GameEntity game = games.findById(gameId).orElseThrow();
        game.setStatus("FINISHED");
        games.save(game);

        List<Map<String, Object>> checkpoints = service.loadReplayCheckpoints(gameId);
        assertFalse(checkpoints.isEmpty());
        // seq 오름차순 정렬
        for (int i = 1; i < checkpoints.size(); i++) {
            assertTrue((long) checkpoints.get(i).get("seq") > (long) checkpoints.get(i - 1).get("seq"));
        }
        // 첫 체크포인트 = "비딩 완료 시점"(비딩 없는 게임이라 처음부터 초기 배치 단계)
        assertEquals("SETUP_MINES", checkpoints.get(0).get("phase"));

        long lastSeq = (long) checkpoints.get(checkpoints.size() - 1).get("seq");
        String json = service.loadReplayStateJson(gameId, lastSeq);
        assertTrue(json.contains("\"phase\""));

        assertThrows(IllegalArgumentException.class, () -> service.loadReplayStateJson(gameId, 999_999L));
    }

    /** SETUP_MINES + SETUP_BOOSTER를 자동으로 진행해 PLAYING까지 도달시킨다 */
    private void driveToPlaying(UUID gameId) {
        GameState state = service.loadLatestState(gameId);
        while ("SETUP_MINES".equals(state.getPhase())) {
            Decision top = state.topDecision();
            String player = top.getTarget();
            String home = data.faction(state.player(player).getFaction()).get("homePlanet").asText();
            String hexKey = state.getHexes().entrySet().stream()
                    .filter(e -> home.equals(e.getValue().getPlanet()) && !e.getValue().hasBuilding())
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            int comma = hexKey.indexOf(',');
            service.submit(gameId, new GameEngine.Submit(player, "PLACE_INITIAL_MINE", top.getId(),
                    Map.of("hexQ", Integer.parseInt(hexKey.substring(0, comma)),
                            "hexR", Integer.parseInt(hexKey.substring(comma + 1)))), null);
            state = service.loadLatestState(gameId);
        }
        while ("SETUP_BOOSTER".equals(state.getPhase())) {
            Decision top = state.topDecision();
            service.submit(gameId, new GameEngine.Submit(top.getTarget(), "CHOOSE_BOOSTER", top.getId(),
                    Map.of("booster", freeBooster(state))), null);
            state = service.loadLatestState(gameId);
        }
    }

    private String freeBooster(GameState state) {
        return state.getBoard().getBoosterHolders().entrySet().stream()
                .filter(e -> e.getValue() == null).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    @Test
    void 경쟁_모드에서는_언두가_거부된다() {
        GameService.CreatedGame created = service.createGame("경쟁", 4L, List.of(
                new GameService.SeatRequest("e1", "GEODENS"),
                new GameService.SeatRequest("e2", "GLEENS"),
                new GameService.SeatRequest("e3", "TERRANS"),
                new GameService.SeatRequest("e4", "NEVLAS")),
                new GameService.GameOptions(false, "NONE"));

        assertThrows(com.gaiaproject.mo_gaia_project_be.engine.EngineException.class,
                () -> service.undoLastAction(created.gameId(), created.playersByNickname().get("e1").toString()));
    }

    @Test
    void 비딩_게임은_경매_낙찰이_플레이어_행에_동기화된다() {
        GameService.CreatedGame created = service.createGame("비딩", 5L, List.of(
                new GameService.SeatRequest("b1", null),
                new GameService.SeatRequest("b2", null),
                new GameService.SeatRequest("b3", null),
                new GameService.SeatRequest("b4", null)),
                new GameService.GameOptions(true, "FREE"));
        UUID gameId = created.gameId();
        String b1 = created.playersByNickname().get("b1").toString();

        GameState state = service.loadLatestState(gameId);
        assertEquals("SETUP_BID", state.getPhase());

        // b1이 1 비딩, 나머지 전원 패스 → b1이 기오덴 선택
        service.submit(gameId, new GameEngine.Submit(b1, "BID_FACTION",
                state.topDecision().getId(), Map.of("bid", 1)), null);
        for (int i = 0; i < 3; i++) {
            state = service.loadLatestState(gameId);
            service.submit(gameId, new GameEngine.Submit(state.topDecision().getTarget(), "BID_FACTION",
                    state.topDecision().getId(), Map.of("pass", true)), null);
        }
        state = service.loadLatestState(gameId);
        assertEquals("CHOOSE_FACTION", state.topDecision().getType());
        String picked = state.getBoard().getFactionPool().get(0); // 후보 4종 중 첫 번째
        service.submit(gameId, new GameEngine.Submit(b1, "CHOOSE_FACTION",
                state.topDecision().getId(), Map.of("faction", picked)), null);

        GamePlayerEntity row = players.findById(new GamePlayerEntity.Key(
                gameId, created.playersByNickname().get("b1"))).orElseThrow();
        assertEquals(picked, row.getFaction());
        assertEquals((short) 1, row.getSeatNo());  // 첫 낙찰 = 턴 순서 1번
        assertEquals((short) 1, row.getBidVp());
    }

    @Test
    void 채팅과_이벤트_로그_조회가_동작한다() {
        GameService.CreatedGame created = service.createGame("채팅", 6L, List.of(
                new GameService.SeatRequest("c1", "GEODENS"),
                new GameService.SeatRequest("c2", "GLEENS"),
                new GameService.SeatRequest("c3", "TERRANS"),
                new GameService.SeatRequest("c4", "NEVLAS")));
        UUID gameId = created.gameId();
        UUID c1 = created.playersByNickname().get("c1");

        chatService.send(gameId, c1, "안녕하세요");
        chatService.send(gameId, c1, "두 번째");

        // 비참가자(관전자)도 발신·열람 모두 가능 (game-spec 12-6, 관전 채팅 허용)
        UUID outsider = users.save(UserAccountEntity.builder()
                .email("out@test").passwordHash("-").nickname("outsider").build()).getId();
        chatService.send(gameId, outsider, "구경 중이에요");

        List<ChatService.ChatView> history = chatService.history(gameId, 0);
        assertEquals(3, history.size());
        assertEquals("안녕하세요", history.get(0).message());
        assertEquals("c1", history.get(0).nickname());
        assertEquals("구경 중이에요", history.get(2).message());
        assertEquals("outsider", history.get(2).nickname());
        assertEquals(2, chatService.history(gameId, 1).size()); // afterSeq 필터

        List<Map<String, Object>> events = service.loadEvents(gameId, 1);
        assertFalse(events.isEmpty());
        assertEquals("GAME_CREATED", events.get(0).get("eventType"));
        assertEquals(1L, ((Number) events.get(0).get("seq")).longValue());
    }
}
