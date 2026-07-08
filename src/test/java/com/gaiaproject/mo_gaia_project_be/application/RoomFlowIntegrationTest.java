package com.gaiaproject.mo_gaia_project_be.application;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        RoomService.RoomView started = rooms.start(room.id(), u1);
        assertEquals("SETUP", started.status());

        GameState state = gameService.loadLatestState(room.id());
        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals(u1.toString(), state.getTurnOrder().get(0)); // 좌석 순 = 턴 순서
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
