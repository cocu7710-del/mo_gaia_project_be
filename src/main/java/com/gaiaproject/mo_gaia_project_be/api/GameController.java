package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.application.ChatService;
import com.gaiaproject.mo_gaia_project_be.application.GameService;
import com.gaiaproject.mo_gaia_project_be.engine.EngineException;
import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 제출·언두의 행위자는 항상 세션 계정 — body의 playerId를 신뢰하지 않는다 (서버 권위). */
@RestController
@RequestMapping("/api/games")
public class GameController {

    public record CreateGameRequest(String name, Long seed, List<GameService.SeatRequest> players,
                                    Boolean bidding, String undoPolicy) {}

    public record SubmitRequest(String type, String decisionId,
                                Map<String, Object> payload, Long expectedVersion) {}

    public record ChatRequest(@NotBlank @Size(max = 500) String message) {}

    private final GameService service;
    private final ChatService chat;
    private final UserRepository users;

    public GameController(GameService service, ChatService chat, UserRepository users) {
        this.service = service;
        this.chat = chat;
        this.users = users;
    }

    /** 개발·테스트용 직접 생성 — 실제 게임은 로비(/api/rooms) 경유 */
    @PostMapping
    public GameService.CreatedGame create(@RequestBody CreateGameRequest request) {
        long seed = request.seed() != null ? request.seed() : UUID.randomUUID().getLeastSignificantBits();
        return service.createGame(request.name(), seed, request.players(),
                new GameService.GameOptions(Boolean.TRUE.equals(request.bidding()), request.undoPolicy()));
    }

    /** 최신 상태 스냅샷 (결정 스택 포함 — 재접속 복원용) */
    @GetMapping(value = "/{gameId}/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public String state(@PathVariable UUID gameId) {
        return service.loadLatestStateJson(gameId);
    }

    /** 결정 제출 — 액션·결정 응답의 단일 진입점 */
    @PostMapping("/{gameId}/actions")
    public GameService.SubmitResult submit(@PathVariable UUID gameId, @RequestBody SubmitRequest request,
                                           Authentication auth) {
        GameEngine.Submit submit = new GameEngine.Submit(
                enginePlayerId(auth), request.type(), request.decisionId(),
                request.payload() == null ? Map.of() : request.payload());
        return service.submit(gameId, submit, request.expectedVersion());
    }

    @PostMapping("/{gameId}/undo")
    public GameService.SubmitResult undo(@PathVariable UUID gameId, Authentication auth) {
        return service.undoLastAction(gameId, enginePlayerId(auth));
    }

    /** 리플레이·관전용 이벤트 로그 */
    @GetMapping("/{gameId}/events")
    public List<Map<String, Object>> events(@PathVariable UUID gameId,
                                            @RequestParam(defaultValue = "1") long fromSeq) {
        return service.loadEvents(gameId, fromSeq);
    }

    @PostMapping("/{gameId}/chat")
    public ChatService.ChatView sendChat(@PathVariable UUID gameId,
                                         @Valid @RequestBody ChatRequest request, Authentication auth) {
        return chat.send(gameId, currentUserId(auth), request.message());
    }

    @GetMapping("/{gameId}/chat")
    public List<ChatService.ChatView> chatHistory(@PathVariable UUID gameId,
                                                  @RequestParam(defaultValue = "0") long afterSeq,
                                                  Authentication auth) {
        return chat.history(gameId, currentUserId(auth), afterSeq);
    }

    private UUID currentUserId(Authentication auth) {
        return users.findByEmail(auth.getName())
                .map(UserAccountEntity::getId)
                .orElseThrow(() -> new IllegalStateException("세션 계정 없음"));
    }

    private String enginePlayerId(Authentication auth) {
        return users.findByEmail(auth.getName())
                .map(UserAccountEntity::getId)
                .orElseThrow(() -> new IllegalStateException("세션 계정 없음"))
                .toString();
    }

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<Map<String, String>> engineError(EngineException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GameService.VersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict(GameService.VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> stateError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
