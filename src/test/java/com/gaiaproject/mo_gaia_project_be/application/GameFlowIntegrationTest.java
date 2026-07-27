package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
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
                Map.of("conversion", "ORE_CREDIT")), null);
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

        // 비참가자 발신·열람 모두 거부 (관전자 채팅 불가)
        UUID outsider = users.save(UserAccountEntity.builder()
                .email("out@test").passwordHash("-").nickname("outsider").build()).getId();
        assertThrows(IllegalStateException.class, () -> chatService.send(gameId, outsider, "끼어들기"));
        assertThrows(IllegalStateException.class, () -> chatService.history(gameId, outsider, 0));

        List<ChatService.ChatView> history = chatService.history(gameId, c1, 0);
        assertEquals(2, history.size());
        assertEquals("안녕하세요", history.get(0).message());
        assertEquals("c1", history.get(0).nickname());
        assertEquals(1, chatService.history(gameId, c1, 1).size()); // afterSeq 필터

        List<Map<String, Object>> events = service.loadEvents(gameId, 1);
        assertFalse(events.isEmpty());
        assertEquals("GAME_CREATED", events.get(0).get("eventType"));
        assertEquals(1L, ((Number) events.get(0).get("seq")).longValue());
    }
}
