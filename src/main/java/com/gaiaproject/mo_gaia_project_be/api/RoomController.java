package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.application.GameService;
import com.gaiaproject.mo_gaia_project_be.application.RoomService;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    public record CreateRoomRequest(@NotBlank @Size(max = 50) String name,
                                    Boolean bidding, String undoPolicy, Integer leechTimerSeconds,
                                    String bidMode, Boolean localMode,
                                    /** 지정 시 이 시드로 셋업 재현 (미지정 시 랜덤) — FE는 정밀도 보존 위해 문자열로 전송 */
                                    Long seed) {}

    public record FactionRequest(@NotBlank String faction) {}

    public record ReadyRequest(boolean ready) {}

    public record KickRequest(UUID userId) {}

    private final RoomService rooms;
    private final UserRepository users;

    public RoomController(RoomService rooms, UserRepository users) {
        this.rooms = rooms;
        this.users = users;
    }

    @PostMapping
    public RoomService.RoomView create(@Valid @RequestBody CreateRoomRequest request, Authentication auth) {
        // 멀티플레이·비딩 모드 b는 아직 테스트 전이라 API 레벨에서도 차단 (FE UI 우회 방지)
        if (!Boolean.TRUE.equals(request.localMode())) {
            throw new IllegalArgumentException("멀티플레이는 아직 테스트 전이라 생성할 수 없습니다 (1인 플레이만 가능)");
        }
        if ("PICK".equals(request.bidMode())) {
            throw new IllegalArgumentException("비딩 모드 b는 아직 테스트 전이라 사용할 수 없습니다");
        }
        return rooms.createRoom(currentUserId(auth), request.name(),
                new GameService.GameOptions(Boolean.TRUE.equals(request.bidding()), request.undoPolicy(),
                        request.leechTimerSeconds(), request.bidMode(), Boolean.TRUE.equals(request.localMode())),
                request.seed());
    }

    @GetMapping
    public List<RoomService.RoomView> list() {
        return rooms.listWaiting();
    }

    /** 로비 진행중 탭 — 내가 참가 중인 진행 게임 */
    @GetMapping("/ongoing")
    public List<RoomService.RoomView> ongoing(Authentication auth) {
        return rooms.listMyOngoing(currentUserId(auth));
    }

    @PostMapping("/{roomId}/ready")
    public RoomService.RoomView ready(@PathVariable UUID roomId,
                                      @RequestBody ReadyRequest request, Authentication auth) {
        return rooms.setReady(roomId, currentUserId(auth), request.ready());
    }

    /** 관전 탭 — 내가 참가하지 않은 진행 게임 */
    @GetMapping("/spectate")
    public List<RoomService.RoomView> spectate(Authentication auth) {
        return rooms.listSpectatable(currentUserId(auth));
    }

    /** 방장 전용 — 시작 전 멤버 강퇴 */
    @PostMapping("/{roomId}/kick")
    public RoomService.RoomView kick(@PathVariable UUID roomId,
                                     @RequestBody KickRequest request, Authentication auth) {
        return rooms.kick(roomId, currentUserId(auth), request.userId());
    }

    @GetMapping("/{roomId}")
    public RoomService.RoomView get(@PathVariable UUID roomId) {
        return rooms.get(roomId);
    }

    @PostMapping("/{roomId}/join")
    public RoomService.RoomView join(@PathVariable UUID roomId, Authentication auth) {
        return rooms.join(roomId, currentUserId(auth));
    }

    @PostMapping("/{roomId}/leave")
    public Map<String, String> leave(@PathVariable UUID roomId, Authentication auth) {
        rooms.leave(roomId, currentUserId(auth));
        return Map.of("status", "ok");
    }

    /** 방장 전용 — 시작 전 방 삭제 */
    @DeleteMapping("/{roomId}")
    public Map<String, String> delete(@PathVariable UUID roomId, Authentication auth) {
        rooms.deleteRoom(roomId, currentUserId(auth));
        return Map.of("status", "ok");
    }

    @PostMapping("/{roomId}/faction")
    public RoomService.RoomView chooseFaction(@PathVariable UUID roomId,
                                              @Valid @RequestBody FactionRequest request, Authentication auth) {
        return rooms.chooseFaction(roomId, currentUserId(auth), request.faction());
    }

    @PostMapping("/{roomId}/start")
    public RoomService.RoomView start(@PathVariable UUID roomId, Authentication auth) {
        return rooms.start(roomId, currentUserId(auth));
    }

    private UUID currentUserId(Authentication auth) {
        return users.findByEmail(auth.getName())
                .map(UserAccountEntity::getId)
                .orElseThrow(() -> new IllegalStateException("세션 계정 없음"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> stateError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
