package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameChatRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameEventRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePendingDecisionRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameSnapshotRepository;
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

    public record MemberView(UUID userId, String nickname, short seatNo, String faction, boolean ready) {}

    /** round·awaitingMe는 진행중/관전 목록에서만 채워진다 (대기 방은 null) */
    public record RoomView(UUID id, String name, String status, UUID createdBy,
                           Map<String, Object> options, List<MemberView> members,
                           Integer round, Boolean awaitingMe) {}

    private final GameRepository games;
    private final GamePlayerRepository players;
    private final UserRepository users;
    private final GameChatRepository chats;
    private final GameSnapshotRepository snapshots;
    private final GameEventRepository events;
    private final GamePendingDecisionRepository pendingDecisions;
    private final GameService gameService;
    private final GameStateCodec codec;
    private final GameData gameData;
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public RoomService(GameRepository games, GamePlayerRepository players, UserRepository users,
                       GameChatRepository chats, GameSnapshotRepository snapshots,
                       GameEventRepository events, GamePendingDecisionRepository pendingDecisions,
                       GameService gameService, GameStateCodec codec,
                       GameData gameData, ObjectProvider<SimpMessagingTemplate> messaging) {
        this.games = games;
        this.players = players;
        this.users = users;
        this.chats = chats;
        this.snapshots = snapshots;
        this.events = events;
        this.pendingDecisions = pendingDecisions;
        this.gameService = gameService;
        this.codec = codec;
        this.gameData = gameData;
        this.messaging = messaging;
    }

    @Transactional
    public RoomView createRoom(UUID creatorId, String name, GameService.GameOptions options) {
        return createRoom(creatorId, name, options, null);
    }

    @Transactional
    public RoomView createRoom(UUID creatorId, String name, GameService.GameOptions options, Long seed) {
        GameEntity game = games.save(GameEntity.builder()
                .name(name)
                .status("WAITING")
                .rulesetVersion(gameData.rulesetVersion())
                .options(codec.writeMap(options.asMap()))
                // 시작 전 확정 — 재생 결정성. 시드 직접 지정 시 같은 셋업 재현 가능
                .rngSeed(seed != null ? seed : ThreadLocalRandom.current().nextLong())
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
        if (isLocalMode(game)) {
            throw new IllegalStateException("1인 플레이 방에는 입장할 수 없습니다");
        }
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
            chats.deleteByGameId(roomId); // 채팅 기록까지 정리 (FK)
            games.delete(game); // 빈 방 해산
            return;
        }
        if (game.getCreatedBy() != null && game.getCreatedBy().equals(userId)) {
            game.setCreatedBy(remaining.get(0).getUserId()); // 방장 위임 (남은 최저 좌석)
        }
        broadcastRoomChange(roomId);
    }

    /**
     * 방장 전용 — 대기 방 삭제, 또는 진행 중(SETUP/PLAYING)인 1인 플레이 게임 삭제.
     * 종료(FINISHED)된 게임이나 진행 중인 일반(다인) 게임은 삭제 대상이 아니다.
     */
    @Transactional
    public void deleteRoom(UUID roomId, UUID requesterId) {
        GameEntity game = games.findByIdForUpdate(roomId)
                .orElseThrow(() -> new IllegalArgumentException("방 없음: " + roomId));
        if (game.getCreatedBy() == null || !game.getCreatedBy().equals(requesterId)) {
            throw new IllegalStateException("방장만 삭제할 수 있습니다");
        }
        boolean waiting = "WAITING".equals(game.getStatus());
        boolean ongoingLocal = isLocalMode(game) && List.of("SETUP", "PLAYING").contains(game.getStatus());
        if (!waiting && !ongoingLocal) {
            throw new IllegalStateException("대기 중인 방 또는 진행 중인 1인 플레이 게임만 삭제할 수 있습니다");
        }
        broadcastRoomChange(roomId); // 커밋 후 알림 → 멤버 FE가 조회 실패로 로비 복귀
        players.deleteAll(players.findByGameIdOrderBySeatNo(roomId));
        chats.deleteByGameId(roomId);
        if (ongoingLocal) {
            pendingDecisions.deleteByGameId(roomId);
            events.deleteByGameId(roomId);
            snapshots.deleteByGameId(roomId);
        }
        games.delete(game);
    }

    /** 준비 토글 — 방장 외 멤버 전원 준비 시에만 시작 가능 */
    @Transactional
    public RoomView setReady(UUID roomId, UUID userId, boolean ready) {
        GameEntity game = requireWaitingRoom(roomId);
        GamePlayerEntity member = players.findById(new GamePlayerEntity.Key(roomId, userId))
                .orElseThrow(() -> new IllegalStateException("방에 입장하지 않았습니다"));
        member.setReady(ready);
        players.save(member);
        broadcastRoomChange(roomId);
        return view(game);
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
        if (!isLocalMode(game)) { // 1인 플레이는 방장 혼자 즉시 시작
            if (members.size() != 4) {
                throw new IllegalStateException("4인이 모여야 시작할 수 있습니다 (현재 " + members.size() + "명)");
            }
            boolean allReady = members.stream()
                    .filter(m -> !m.getUserId().equals(requesterId)) // 방장은 준비 불필요
                    .allMatch(GamePlayerEntity::isReady);
            if (!allReady) {
                throw new IllegalStateException("전원이 준비를 완료해야 시작할 수 있습니다");
            }
        }
        gameService.startGame(game, members);
        broadcastRoomChange(roomId);
        return view(game);
    }

    @Transactional(readOnly = true)
    public List<RoomView> listWaiting() {
        return games.findByStatusOrderByCreatedAtDesc("WAITING").stream()
                .filter(game -> !isLocalMode(game)) // 1인 플레이 방은 모집 목록에서 제외
                .map(this::view).toList();
    }

    /** 진행중 탭 — 내가 참가 중인 진행 게임 (라운드·내 차례 여부 포함) */
    @Transactional(readOnly = true)
    public List<RoomView> listMyOngoing(UUID userId) {
        return games.findOngoingByUserId(userId).stream()
                .map(game -> ongoingView(game, userId)).toList();
    }

    /** 관전 탭 — 내가 참가하지 않은 진행 게임 */
    @Transactional(readOnly = true)
    public List<RoomView> listSpectatable(UUID userId) {
        return games.findOngoingExcludingUser(userId).stream()
                .map(game -> view(game, null, null)).toList();
    }

    /** 완료 탭 — 내가 참가했던 완료 게임 (나간 뒤에도 다시 들어가서 최종 결과 확인) */
    @Transactional(readOnly = true)
    public List<RoomView> listMyFinished(UUID userId) {
        return games.findFinishedByUserId(userId).stream()
                .map(game -> ongoingView(game, userId)).toList();
    }

    /** 방장 전용 — 시작 전 멤버 강퇴 */
    @Transactional
    public RoomView kick(UUID roomId, UUID requesterId, UUID targetUserId) {
        GameEntity game = requireWaitingRoom(roomId);
        if (game.getCreatedBy() == null || !game.getCreatedBy().equals(requesterId)) {
            throw new IllegalStateException("방장만 강퇴할 수 있습니다");
        }
        if (requesterId.equals(targetUserId)) {
            throw new IllegalStateException("자기 자신은 강퇴할 수 없습니다 (나가기 사용)");
        }
        GamePlayerEntity target = players.findById(new GamePlayerEntity.Key(roomId, targetUserId))
                .orElseThrow(() -> new IllegalStateException("해당 유저는 방에 없습니다"));
        players.delete(target);
        broadcastRoomChange(roomId);
        return view(game);
    }

    @Transactional(readOnly = true)
    public RoomView get(UUID roomId) {
        return view(games.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("방 없음: " + roomId)));
    }

    // ═══════════════ 내부 ═══════════════

    private boolean isLocalMode(GameEntity game) {
        return Boolean.TRUE.equals(codec.readMap(game.getOptions()).get("localMode"));
    }

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
        return view(game, null, null);
    }

    private RoomView view(GameEntity game, Integer round, Boolean awaitingMe) {
        List<MemberView> members = new ArrayList<>();
        for (GamePlayerEntity m : players.findByGameIdOrderBySeatNo(game.getId())) {
            String nickname = users.findById(m.getUserId())
                    .map(UserAccountEntity::getNickname).orElse("?");
            members.add(new MemberView(m.getUserId(), nickname, m.getSeatNo(), m.getFaction(), m.isReady()));
        }
        return new RoomView(game.getId(), game.getName(), game.getStatus(), game.getCreatedBy(),
                codec.readMap(game.getOptions()), members, round, awaitingMe);
    }

    /** 진행 게임의 라운드·내 입력 대기 여부 (스냅샷 기반) */
    private RoomView ongoingView(GameEntity game, UUID userId) {
        Integer round = null;
        Boolean awaitingMe = null;
        var snapshot = snapshots.findFirstByGameIdOrderBySeqDesc(game.getId());
        if (snapshot.isPresent()) {
            var state = codec.read(snapshot.get().getState());
            round = state.getRound();
            String me = userId.toString();
            var top = state.topDecision();
            awaitingMe = top != null
                    ? GameService.isSeatOf(top.getTarget(), me)
                    : "PLAYING".equals(state.getPhase()) && GameService.isSeatOf(state.getActivePlayer(), me);
        }
        return view(game, round, awaitingMe);
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
