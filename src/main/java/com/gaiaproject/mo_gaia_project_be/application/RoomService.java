package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 로비 방 = game 테이블의 WAITING 행. 시작 시 GameService.startGame으로 상태를 초기화한다.
 * 방 변경은 /topic/room/{id}로 브로드캐스트 — FE는 수신 시 방 상태를 재조회한다.
 */
@Service
public class RoomService {

    public record MemberView(UUID userId, String nickname, short seatNo, String faction) {}

    public record RoomView(UUID id, String name, String status, UUID createdBy,
                           Map<String, Object> options, List<MemberView> members) {}

    private final GameRepository games;
    private final GamePlayerRepository players;
    private final UserRepository users;
    private final GameService gameService;
    private final GameStateCodec codec;
    private final GameData gameData;
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public RoomService(GameRepository games, GamePlayerRepository players, UserRepository users,
                       GameService gameService, GameStateCodec codec, GameData gameData,
                       ObjectProvider<SimpMessagingTemplate> messaging) {
        this.games = games;
        this.players = players;
        this.users = users;
        this.gameService = gameService;
        this.codec = codec;
        this.gameData = gameData;
        this.messaging = messaging;
    }

    @Transactional
    public RoomView createRoom(UUID creatorId, String name, GameService.GameOptions options) {
        GameEntity game = games.save(GameEntity.builder()
                .name(name)
                .status("WAITING")
                .rulesetVersion(gameData.rulesetVersion())
                .options(codec.writeMap(options.asMap()))
                .rngSeed(ThreadLocalRandom.current().nextLong()) // 시작 전 확정 — 재생 결정성
                .lastSeq(0)
                .createdBy(creatorId)
                .build());
        players.save(GamePlayerEntity.builder()
                .gameId(game.getId()).userId(creatorId).seatNo((short) 1).bidVp((short) 0)
                .build());
        broadcastRoomChange(game.getId());
        return view(game);
    }

    @Transactional
    public RoomView join(UUID roomId, UUID userId) {
        GameEntity game = requireWaitingRoom(roomId);
        List<GamePlayerEntity> members = players.findByGameIdOrderBySeatNo(roomId);
        if (members.stream().anyMatch(m -> m.getUserId().equals(userId))) {
            return view(game); // 이미 입장 — 멱등
        }
        if (members.size() >= 4) {
            throw new IllegalStateException("방이 가득 찼습니다");
        }
        players.save(GamePlayerEntity.builder()
                .gameId(roomId).userId(userId).seatNo(nextSeat(members)).bidVp((short) 0)
                .build());
        broadcastRoomChange(roomId);
        return view(game);
    }

    @Transactional
    public void leave(UUID roomId, UUID userId) {
        GameEntity game = requireWaitingRoom(roomId);
        List<GamePlayerEntity> members = players.findByGameIdOrderBySeatNo(roomId);
        GamePlayerEntity leaving = members.stream()
                .filter(m -> m.getUserId().equals(userId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("방에 입장하지 않았습니다"));

        players.delete(leaving);
        List<GamePlayerEntity> remaining = members.stream()
                .filter(m -> !m.getUserId().equals(userId)).toList();
        if (remaining.isEmpty()) {
            games.delete(game); // 빈 방 해산
            return;
        }
        if (game.getCreatedBy() != null && game.getCreatedBy().equals(userId)) {
            game.setCreatedBy(remaining.get(0).getUserId()); // 방장 위임 (남은 최저 좌석)
        }
        broadcastRoomChange(roomId);
    }

    /** 비딩 방이 아닐 때만 — 시작 전 종족 선택 (중복 불가) */
    @Transactional
    public RoomView chooseFaction(UUID roomId, UUID userId, String faction) {
        GameEntity game = requireWaitingRoom(roomId);
        if (Boolean.TRUE.equals(codec.readMap(game.getOptions()).get("bidding"))) {
            throw new IllegalStateException("비딩 방은 경매로 종족이 정해집니다");
        }
        gameData.faction(faction); // 존재 검증 (없으면 예외)
        List<GamePlayerEntity> members = players.findByGameIdOrderBySeatNo(roomId);
        if (members.stream().anyMatch(m -> !m.getUserId().equals(userId) && faction.equals(m.getFaction()))) {
            throw new IllegalStateException("이미 선택된 종족입니다: " + faction);
        }
        GamePlayerEntity me = members.stream()
                .filter(m -> m.getUserId().equals(userId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("방에 입장하지 않았습니다"));
        me.setFaction(faction);
        players.save(me);
        broadcastRoomChange(roomId);
        return view(game);
    }

    /** 방장만, 4인 충족 시 시작 → SETUP(비딩 또는 초기 배치)으로 전환 */
    @Transactional
    public RoomView start(UUID roomId, UUID requesterId) {
        GameEntity game = games.findByIdForUpdate(roomId)
                .orElseThrow(() -> new IllegalArgumentException("방 없음: " + roomId));
        if (!"WAITING".equals(game.getStatus())) {
            throw new IllegalStateException("이미 시작된 게임입니다");
        }
        if (game.getCreatedBy() == null || !game.getCreatedBy().equals(requesterId)) {
            throw new IllegalStateException("방장만 시작할 수 있습니다");
        }
        List<GamePlayerEntity> members = players.findByGameIdOrderBySeatNo(roomId);
        if (members.size() != 4) {
            throw new IllegalStateException("4인이 모여야 시작할 수 있습니다 (현재 " + members.size() + "명)");
        }
        gameService.startGame(game, members);
        broadcastRoomChange(roomId);
        return view(game);
    }

    @Transactional(readOnly = true)
    public List<RoomView> listWaiting() {
        return games.findByStatusOrderByCreatedAtDesc("WAITING").stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RoomView get(UUID roomId) {
        return view(games.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("방 없음: " + roomId)));
    }

    // ═══════════════ 내부 ═══════════════

    private GameEntity requireWaitingRoom(UUID roomId) {
        GameEntity game = games.findByIdForUpdate(roomId)
                .orElseThrow(() -> new IllegalArgumentException("방 없음: " + roomId));
        if (!"WAITING".equals(game.getStatus())) {
            throw new IllegalStateException("대기 중인 방이 아닙니다");
        }
        return game;
    }

    private short nextSeat(List<GamePlayerEntity> members) {
        for (short seat = 1; seat <= 4; seat++) {
            short s = seat;
            if (members.stream().noneMatch(m -> m.getSeatNo() == s)) {
                return seat;
            }
        }
        throw new IllegalStateException("빈 좌석이 없습니다");
    }

    private RoomView view(GameEntity game) {
        List<MemberView> members = new ArrayList<>();
        for (GamePlayerEntity m : players.findByGameIdOrderBySeatNo(game.getId())) {
            String nickname = users.findById(m.getUserId())
                    .map(UserAccountEntity::getNickname).orElse("?");
            members.add(new MemberView(m.getUserId(), nickname, m.getSeatNo(), m.getFaction()));
        }
        return new RoomView(game.getId(), game.getName(), game.getStatus(), game.getCreatedBy(),
                codec.readMap(game.getOptions()), members);
    }

    private void broadcastRoomChange(UUID roomId) {
        SimpMessagingTemplate template = messaging.getIfAvailable();
        if (template == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                template.convertAndSend("/topic/room/" + roomId, (Object) Map.of("type", "ROOM_UPDATED"));
            }
        });
    }
}
