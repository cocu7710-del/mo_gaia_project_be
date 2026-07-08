package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.EngineEvent;
import com.gaiaproject.mo_gaia_project_be.engine.EngineException;
import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.engine.GameSetup;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameEventEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePendingDecisionEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameSnapshotEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserSettingsEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameEventRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePendingDecisionRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameSnapshotRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserSettingsRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 게임 트랜잭션 서비스 — 결정 하나 = 트랜잭션 하나 (락 → 검증 → 엔진 적용 → 이벤트/스냅샷 저장 → 커밋 → 브로드캐스트).
 * 진실은 이벤트 로그, 상태 복원은 스냅샷.
 * 스냅샷: 결정 스택이 빈 시점(턴 경계)만 CHECKPOINT로 영구 보관, 연쇄 중간은 최신 1개(AFTER_EVENT)만 유지.
 * 언두 복원점은 메인 액션 직전 = 항상 턴 경계이므로 체크포인트로 충분하다.
 */
@Service
public class GameService {

    public record SeatRequest(String nickname, String faction) {}

    /** 방 옵션 — undoPolicy: FREE(친선)/CONSENT(표준)/NONE(경쟁), leechTimerSeconds: 리치 응답 제한(null=끔) */
    public record GameOptions(boolean bidding, String undoPolicy, Integer leechTimerSeconds) {
        public GameOptions {
            if (undoPolicy == null) {
                undoPolicy = "FREE";
            }
            if (!List.of("FREE", "CONSENT", "NONE").contains(undoPolicy)) {
                throw new IllegalArgumentException("알 수 없는 언두 정책: " + undoPolicy);
            }
            if (leechTimerSeconds != null && leechTimerSeconds < 5) {
                throw new IllegalArgumentException("리치 타이머는 5초 이상이어야 합니다");
            }
        }

        public GameOptions(boolean bidding, String undoPolicy) {
            this(bidding, undoPolicy, null);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("bidding", bidding);
            map.put("undoPolicy", undoPolicy);
            if (leechTimerSeconds != null) {
                map.put("leechTimerSeconds", leechTimerSeconds);
            }
            return map;
        }
    }

    public record CreatedGame(UUID gameId, Map<String, UUID> playersByNickname) {}

    public record SubmitResult(long version, List<EngineEvent> events) {}

    public static class VersionConflictException extends RuntimeException {
        public VersionConflictException(long expected, long actual) {
            super("버전 불일치 (제출: " + expected + ", 현재: " + actual + ") — 상태를 리싱크하세요");
        }
    }

    private final GameRepository games;
    private final GamePlayerRepository players;
    private final GameEventRepository events;
    private final GameSnapshotRepository snapshots;
    private final GamePendingDecisionRepository pendingDecisions;
    private final UserRepository users;
    private final UserSettingsRepository userSettings;
    private final GameEngine engine;
    private final com.gaiaproject.mo_gaia_project_be.engine.rules.GameData gameData;
    private final GameStateCodec codec;
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public GameService(GameRepository games, GamePlayerRepository players, GameEventRepository events,
                       GameSnapshotRepository snapshots, GamePendingDecisionRepository pendingDecisions,
                       UserRepository users, UserSettingsRepository userSettings, GameEngine engine,
                       com.gaiaproject.mo_gaia_project_be.engine.rules.GameData gameData,
                       GameStateCodec codec, ObjectProvider<SimpMessagingTemplate> messaging) {
        this.games = games;
        this.players = players;
        this.events = events;
        this.snapshots = snapshots;
        this.pendingDecisions = pendingDecisions;
        this.users = users;
        this.userSettings = userSettings;
        this.engine = engine;
        this.gameData = gameData;
        this.codec = codec;
        this.messaging = messaging;
    }

    // ═══════════════ 게임 생성 ═══════════════

    @Transactional
    public CreatedGame createGame(String name, long seed, List<SeatRequest> seats) {
        return createGame(name, seed, seats, new GameOptions(false, "FREE"));
    }

    /** bidding=true면 종족은 SETUP_BID 경매로 확정 — SeatRequest.faction은 무시된다 */
    @Transactional
    public CreatedGame createGame(String name, long seed, List<SeatRequest> seats, GameOptions options) {
        boolean bidding = options.bidding();
        if (seats.size() != 4) {
            throw new IllegalArgumentException("4인 전용 게임입니다");
        }
        Map<String, UUID> byNickname = new LinkedHashMap<>();
        List<GameSetup.PlayerSeat> engineSeats = new ArrayList<>();
        for (SeatRequest seat : seats) {
            UUID userId = users.findByNickname(seat.nickname())
                    .orElseGet(() -> users.save(UserAccountEntity.builder()
                            .email(seat.nickname() + "@local")
                            .passwordHash("-")
                            .nickname(seat.nickname())
                            .build()))
                    .getId();
            byNickname.put(seat.nickname(), userId);
            engineSeats.add(new GameSetup.PlayerSeat(userId.toString(), seat.faction()));
        }

        GameState state = bidding
                ? GameSetup.createWithBidding(gameData, seed,
                        engineSeats.stream().map(GameSetup.PlayerSeat::playerId).toList())
                : GameSetup.create(gameData, seed, engineSeats);

        GameEntity game = games.save(GameEntity.builder()
                .name(name)
                .status(statusOf(state))
                .rulesetVersion(gameData.rulesetVersion())
                .options(codec.writeMap(options.asMap()))
                .rngSeed(seed)
                .lastSeq(1)
                .build());

        short seatNo = 1;
        for (SeatRequest seat : seats) {
            players.save(GamePlayerEntity.builder()
                    .gameId(game.getId())
                    .userId(byNickname.get(seat.nickname()))
                    .seatNo(seatNo++) // 비딩 모드에선 입장 순서 — 낙찰 시 FACTION_ASSIGNED로 갱신
                    .faction(bidding ? null : seat.faction())
                    .bidVp((short) 0)
                    .build());
        }

        persistInitialState(game, state, "GAME_CREATED");

        return new CreatedGame(game.getId(), byNickname);
    }

    /**
     * 로비 방(WAITING) → 게임 시작. RoomService의 트랜잭션 안에서 호출된다 (락은 호출부가 보유).
     * 비딩 방은 SETUP_BID로, 아니면 좌석의 종족으로 바로 SETUP_MINES로 진입한다.
     */
    public void startGame(GameEntity game, List<GamePlayerEntity> members) {
        Map<String, Object> options = codec.readMap(game.getOptions());
        GameState state;
        if (Boolean.TRUE.equals(options.get("bidding"))) {
            state = GameSetup.createWithBidding(gameData, game.getRngSeed(),
                    members.stream().map(m -> m.getUserId().toString()).toList());
        } else {
            List<GameSetup.PlayerSeat> seats = new ArrayList<>();
            for (GamePlayerEntity member : members) {
                if (member.getFaction() == null) {
                    throw new IllegalStateException("종족을 선택하지 않은 플레이어가 있습니다 (좌석 " + member.getSeatNo() + ")");
                }
                seats.add(new GameSetup.PlayerSeat(member.getUserId().toString(), member.getFaction()));
            }
            state = GameSetup.create(gameData, game.getRngSeed(), seats);
        }
        persistInitialState(game, state, "GAME_STARTED");
    }

    private void persistInitialState(GameEntity game, GameState state, String eventType) {
        state.setVersion(1);
        game.setLastSeq(1);
        game.setStatus(statusOf(state));
        events.save(GameEventEntity.builder()
                .gameId(game.getId()).seq(1).eventType(eventType).actor(null)
                .payload(codec.writeMap(Map.of("seed", game.getRngSeed(), "name", game.getName())))
                .build());
        snapshots.save(GameSnapshotEntity.builder()
                .gameId(game.getId()).seq(1).snapshotType("SETUP_DONE").state(codec.write(state))
                .build());
        rebuildPendingProjection(game, state, 1);
    }

    // ═══════════════ 결정 제출 ═══════════════

    @Transactional
    public SubmitResult submit(UUID gameId, GameEngine.Submit submit, Long expectedVersion) {
        GameEntity game = games.findByIdForUpdate(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임 없음: " + gameId));
        if (expectedVersion != null && expectedVersion != game.getLastSeq()) {
            throw new VersionConflictException(expectedVersion, game.getLastSeq());
        }
        GameState state = loadLatestState(gameId);

        List<EngineEvent> engineEvents = new ArrayList<>(
                engine.apply(state, submit)); // 검증 실패 시 EngineException → 롤백
        autoResolveLeech(state, engineEvents); // ② 개인 설정 자동 수락/거절

        long seq = game.getLastSeq();
        for (EngineEvent event : engineEvents) {
            seq++;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("submitType", submit.type());
            if (submit.decisionId() != null) {
                payload.put("decisionId", submit.decisionId());
            }
            payload.putAll(event.payload());
            events.save(GameEventEntity.builder()
                    .gameId(gameId).seq(seq).eventType(event.type()).actor(event.actor())
                    .payload(codec.writeMap(payload))
                    .build());
            if ("FACTION_ASSIGNED".equals(event.type())) {
                syncPlayerFaction(gameId, event); // 비딩 낙찰 → 종족·좌석·비딩값 반영
            }
        }
        state.setVersion(seq);
        saveSnapshot(gameId, seq, state);
        game.setLastSeq(seq);
        game.setStatus(statusOf(state));
        rebuildPendingProjection(game, state, seq);

        broadcastAfterCommit(gameId, seq, engineEvents);
        return new SubmitResult(seq, engineEvents);
    }

    // ═══════════════ 턴 초기화 (언두) ═══════════════

    /**
     * 요청자의 마지막 메인 액션(ACTION_*) 직전 상태로 복원.
     * 언두 정책(방 옵션): FREE 자유 / NONE 금지 / CONSENT — 상대의 수동 응답이 있으면 거부
     * (동의 요청·승인 플로우는 로비 단계에서 추가. 자동 리치 수락은 별도 이벤트가 아니라 현재 미탐지).
     */
    @Transactional
    public SubmitResult undoLastAction(UUID gameId, String playerId) {
        GameEntity game = games.findByIdForUpdate(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임 없음: " + gameId));
        String policy = (String) codec.readMap(game.getOptions()).getOrDefault("undoPolicy", "FREE");
        if ("NONE".equals(policy)) {
            throw new EngineException("경쟁 모드에서는 언두할 수 없습니다");
        }

        List<GameEventEntity> live = events.findByGameIdAndUndoneByIsNullOrderBySeqDesc(gameId);
        GameEventEntity target = live.stream()
                .filter(e -> playerId.equals(e.getActor()) && e.getEventType().startsWith("ACTION_"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("되돌릴 액션이 없습니다"));
        if ("CONSENT".equals(policy)) {
            boolean opponentResponded = live.stream().anyMatch(e ->
                    e.getSeq() > target.getSeq() && e.getActor() != null && !playerId.equals(e.getActor()));
            if (opponentResponded) {
                throw new EngineException("상대 응답 이후의 언두는 동의가 필요합니다");
            }
        }

        long restoreSeq = target.getSeq() - 1;
        GameSnapshotEntity restore = snapshots.findByGameIdAndSeq(gameId, restoreSeq)
                .orElseThrow(() -> new IllegalStateException("복원 스냅샷 없음: " + restoreSeq));
        GameState state = codec.read(restore.getState());

        long undoSeq = game.getLastSeq() + 1;
        // append-only: 무효 구간 마킹 + TURN_UNDONE 이벤트 추가 (삭제하지 않음)
        for (GameEventEntity e : live) {
            if (e.getSeq() >= target.getSeq()) {
                e.setUndoneBy(undoSeq);
                events.save(e);
            }
        }
        EngineEvent undoEvent = new EngineEvent("TURN_UNDONE", playerId,
                Map.of("undoneFromSeq", target.getSeq(), "undoneToSeq", game.getLastSeq(), "restoredSeq", restoreSeq));
        events.save(GameEventEntity.builder()
                .gameId(gameId).seq(undoSeq).eventType("TURN_UNDONE").actor(playerId)
                .payload(codec.writeMap(new LinkedHashMap<>(undoEvent.payload())))
                .build());

        state.setVersion(undoSeq);
        saveSnapshot(gameId, undoSeq, state);
        game.setLastSeq(undoSeq);
        game.setStatus(statusOf(state));
        rebuildPendingProjection(game, state, undoSeq);

        broadcastAfterCommit(gameId, undoSeq, List.of(undoEvent));
        return new SubmitResult(undoSeq, List.of(undoEvent));
    }

    // ═══════════════ 조회 ═══════════════

    @Transactional(readOnly = true)
    public GameState loadLatestState(UUID gameId) {
        GameSnapshotEntity snapshot = snapshots.findFirstByGameIdOrderBySeqDesc(gameId)
                .orElseThrow(() -> new IllegalArgumentException("스냅샷 없음: " + gameId));
        return codec.read(snapshot.getState());
    }

    @Transactional(readOnly = true)
    public String loadLatestStateJson(UUID gameId) {
        return snapshots.findFirstByGameIdOrderBySeqDesc(gameId)
                .orElseThrow(() -> new IllegalArgumentException("스냅샷 없음: " + gameId))
                .getState();
    }

    /** 리플레이·관전용 이벤트 로그 — undoneBy가 있는 행은 무효 구간(FE가 필터·표시) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> loadEvents(UUID gameId, long fromSeq) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GameEventEntity e : events.findByGameIdAndSeqGreaterThanEqualOrderBySeq(gameId, fromSeq)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", e.getSeq());
            row.put("eventType", e.getEventType());
            row.put("actor", e.getActor());
            row.put("payload", codec.readMap(e.getPayload()));
            row.put("undoneBy", e.getUndoneBy());
            result.add(row);
        }
        return result;
    }

    // ═══════════════ 내부 ═══════════════

    /** 턴 경계(스택 빈 상태)는 CHECKPOINT로 영구 보관, 결정 연쇄 중간은 최신 1개(AFTER_EVENT)만 유지 */
    private void saveSnapshot(UUID gameId, long seq, GameState state) {
        snapshots.deleteByGameIdAndSnapshotType(gameId, "AFTER_EVENT");
        String type = state.getDecisionStack().isEmpty() ? "CHECKPOINT" : "AFTER_EVENT";
        snapshots.save(GameSnapshotEntity.builder()
                .gameId(gameId).seq(seq).snapshotType(type).state(codec.write(state))
                .build());
    }

    @SuppressWarnings("unchecked")
    private void syncPlayerFaction(UUID gameId, EngineEvent event) {
        Map<String, Object> effects = (Map<String, Object>) event.payload().get("effects");
        players.findById(new GamePlayerEntity.Key(gameId, UUID.fromString(event.actor())))
                .ifPresent(row -> {
                    row.setFaction((String) effects.get("faction"));
                    row.setSeatNo(((Number) effects.get("seatNo")).shortValue());
                    row.setBidVp(((Number) effects.get("bidVp")).shortValue());
                    players.save(row);
                });
    }

    /**
     * ② 개인 설정(승점 비용 X 이하 자동 수락 / Y 이상 자동 거절) 자동 응답 —
     * 무비용 오퍼(아이타·타클론 수동 예외)와 추가 선택이 있는 리치(타클론 PI)는 자동에서 제외.
     */
    private void autoResolveLeech(GameState state, List<EngineEvent> engineEvents) {
        while (!state.getDecisionStack().isEmpty()) {
            Decision top = state.topDecision();
            if (!"LEECH_RESPONSE".equals(top.getType())
                    || Boolean.TRUE.equals(top.getContext().get("taklonsPi"))) {
                return;
            }
            int vpCost = ((Number) top.getContext().getOrDefault("vpCost", 0)).intValue();
            Boolean accept = decideAutoLeech(vpCost, settingsOf(top.getTarget()));
            if (accept == null) {
                return;
            }
            engineEvents.addAll(engine.apply(state, new GameEngine.Submit(
                    top.getTarget(), "LEECH_RESPONSE", top.getId(), Map.of("accept", accept, "auto", true))));
        }
    }

    /** true=자동 수락, false=자동 거절, null=수동 대기. 무비용(vpCost 0) 오퍼는 항상 수동 유지. */
    static Boolean decideAutoLeech(int vpCost, UserSettingsEntity settings) {
        if (vpCost < 1 || settings == null) {
            return null;
        }
        if (vpCost <= settings.getLeechAutoAcceptMaxVp()) {
            return true;
        }
        if (settings.getLeechAutoDeclineMinVp() != null && vpCost >= settings.getLeechAutoDeclineMinVp()) {
            return false;
        }
        return null;
    }

    private UserSettingsEntity settingsOf(String enginePlayerId) {
        try {
            return userSettings.findById(UUID.fromString(enginePlayerId)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null; // 엔진 테스트용 비 UUID id — 설정 없음 취급
        }
    }

    private void rebuildPendingProjection(GameEntity game, GameState state, long seq) {
        UUID gameId = game.getId();
        Object timer = codec.readMap(game.getOptions()).get("leechTimerSeconds");
        pendingDecisions.deleteByGameId(gameId);
        int order = 0;
        for (Decision decision : state.getDecisionStack()) {
            // ③ 응답 타이머: 현재 응답 가능한(최상단) 리치 오퍼에만 마감 부여
            boolean timed = timer instanceof Number seconds
                    && decision == state.topDecision()
                    && "LEECH_RESPONSE".equals(decision.getType());
            pendingDecisions.save(GamePendingDecisionEntity.builder()
                    .gameId(gameId)
                    .decisionId(decision.getId())
                    .stackOrder(order++)
                    .targetPlayer(decision.getTarget())
                    .decisionType(decision.getType())
                    .context(codec.writeMap(new LinkedHashMap<>(decision.getContext())))
                    .deadlineAt(timed ? OffsetDateTime.now().plusSeconds(((Number) timer).longValue()) : null)
                    .createdSeq(seq)
                    .build());
        }
    }

    private String statusOf(GameState state) {
        return switch (state.getPhase()) {
            case "FINISHED" -> "FINISHED";
            case "PLAYING" -> "PLAYING";
            default -> "SETUP";
        };
    }

    /** 커밋 이후에만 브로드캐스트 — 유실 시 클라이언트가 버전 불일치를 감지하고 리싱크 */
    private void broadcastAfterCommit(UUID gameId, long version, List<EngineEvent> engineEvents) {
        SimpMessagingTemplate template = messaging.getIfAvailable();
        if (template == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<Map<String, Object>> eventViews = engineEvents.stream()
                .map(e -> Map.<String, Object>of("type", e.type(), "actor", e.actor() == null ? "" : e.actor(),
                        "payload", e.payload()))
                .toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                template.convertAndSend("/topic/game/" + gameId,
                        (Object) Map.of("version", version, "events", eventViews));
            }
        });
    }
}
