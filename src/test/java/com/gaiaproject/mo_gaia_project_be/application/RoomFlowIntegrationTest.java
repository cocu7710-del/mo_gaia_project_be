package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 로비 방 수직 흐름 — 생성 → 입장 → 종족 선택 → 시작 → 게임 상태 초기화. Docker 미가동 시 스킵. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RoomFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    RoomService rooms;

    @Autowired
    GameService gameService;

    @Autowired
    UserRepository users;

    @Autowired
    ChatService chatService;

    private UUID user(String nickname) {
        return users.findByNickname(nickname).orElseGet(() -> users.save(UserAccountEntity.builder()
                .email(nickname + "@test").passwordHash("-").nickname(nickname).build())).getId();
    }

    @Test
    void 방_생성부터_게임_시작까지_동작한다() {
        UUID u1 = user("r1");
        UUID u2 = user("r2");
        UUID u3 = user("r3");
        UUID u4 = user("r4");
        RoomService.RoomView room = rooms.createRoom(u1, "테스트방",
                new GameService.GameOptions(false, "CONSENT"));
        rooms.join(room.id(), u2);
        rooms.join(room.id(), u3);
        rooms.join(room.id(), u4);

        UUID u5 = user("r5");
        assertThrows(IllegalStateException.class, () -> rooms.join(room.id(), u5)); // 정원 초과

        assertThrows(IllegalStateException.class, () -> rooms.start(room.id(), u1)); // 종족 미선택

        rooms.chooseFaction(room.id(), u1, "GEODENS");
        assertThrows(IllegalStateException.class,
                () -> rooms.chooseFaction(room.id(), u2, "GEODENS")); // 중복 종족
        rooms.chooseFaction(room.id(), u2, "GLEENS");
        rooms.chooseFaction(room.id(), u3, "TERRANS");
        rooms.chooseFaction(room.id(), u4, "NEVLAS");

        assertThrows(IllegalStateException.class, () -> rooms.start(room.id(), u2)); // 방장 아님
        assertThrows(IllegalStateException.class, () -> rooms.start(room.id(), u1)); // 준비 미완료

        rooms.setReady(room.id(), u2, true);
        rooms.setReady(room.id(), u3, true);
        rooms.setReady(room.id(), u4, true);

        RoomService.RoomView started = rooms.start(room.id(), u1);
        assertEquals("SETUP", started.status());

        GameState state = gameService.loadLatestState(room.id());
        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals(u1.toString(), state.getTurnOrder().get(0)); // 좌석 순 = 턴 순서
    }

    @Test
    void 로컬_모드는_방장_혼자_시작하고_전_좌석을_조작한다() {
        UUID host = user("solo1");
        RoomService.RoomView room = rooms.createRoom(host, "1인 플레이방",
                new GameService.GameOptions(true, "CONSENT", null, "ORDER", true));

        // 모집 목록 제외 + 타 유저 입장 불가
        assertTrue(rooms.listWaiting().stream().noneMatch(r -> r.id().equals(room.id())));
        UUID other = user("solo2");
        assertThrows(IllegalStateException.class, () -> rooms.join(room.id(), other));

        // 방장 혼자 Ready 없이 즉시 시작
        RoomService.RoomView started = rooms.start(room.id(), host);
        assertEquals("SETUP", started.status());
        assertEquals("SETUP_BID", gameService.loadLatestState(room.id()).getPhase());

        // 전 좌석을 방장 세션 id로 제출 — 서버가 현재 차례 좌석으로 귀속
        String me = host.toString();
        GameState state = gameService.loadLatestState(room.id());
        while ("SETUP_BID".equals(state.getPhase())) {
            Decision top = state.topDecision();
            Map<String, Object> payload = switch (top.getType()) {
                case "BID_FACTION" -> state.getBoard().getBidLeader() == null
                        ? Map.of("bid", 1) : Map.of("pass", true);
                default -> Map.of("faction", state.getBoard().getFactionPool().get(0));
            };
            gameService.submit(room.id(),
                    new GameEngine.Submit(me, top.getType(), top.getId(), payload), null);
            state = gameService.loadLatestState(room.id());
        }
        assertEquals("SETUP_MINES", state.getPhase());
        assertTrue(state.getTurnOrder().stream().allMatch(p -> p.startsWith(me + "#")));

        // 비참가자는 로컬 게임에 제출 불가
        GameState current = state;
        assertThrows(Exception.class, () -> gameService.submit(room.id(), new GameEngine.Submit(
                other.toString(), current.topDecision().getType(), current.topDecision().getId(), Map.of()), null));

        // 진행중 탭 — 항상 내 차례
        RoomService.RoomView ongoing = rooms.listMyOngoing(host).stream()
                .filter(r -> r.id().equals(room.id())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, ongoing.awaitingMe());
    }

    @Test
    void 방장은_채팅_기록이_있어도_방을_삭제할_수_있다() {
        UUID u1 = user("d1");
        UUID u2 = user("d2");
        RoomService.RoomView room = rooms.createRoom(u1, "삭제방",
                new GameService.GameOptions(false, "FREE"));
        rooms.join(room.id(), u2);
        chatService.send(room.id(), u1, "삭제 전 채팅");

        assertThrows(IllegalStateException.class, () -> rooms.deleteRoom(room.id(), u2)); // 방장 아님

        rooms.deleteRoom(room.id(), u1);
        assertThrows(IllegalArgumentException.class, () -> rooms.get(room.id()));
    }

    @Test
    void 방장이_나가면_위임되고_빈_방은_해산된다() {
        UUID u1 = user("h1");
        UUID u2 = user("h2");
        RoomService.RoomView room = rooms.createRoom(u1, "위임방",
                new GameService.GameOptions(false, "FREE"));
        rooms.join(room.id(), u2);

        rooms.leave(room.id(), u1);
        assertEquals(u2, rooms.get(room.id()).createdBy());

        rooms.leave(room.id(), u2);
        assertThrows(IllegalArgumentException.class, () -> rooms.get(room.id())); // 해산됨
    }
}
