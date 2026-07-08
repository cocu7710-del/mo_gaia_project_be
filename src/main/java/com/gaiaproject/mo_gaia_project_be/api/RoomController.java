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
                                    Boolean bidding, String undoPolicy, Integer leechTimerSeconds) {}

    public record FactionRequest(@NotBlank String faction) {}

    private final RoomService rooms;
    private final UserRepository users;

    public RoomController(RoomService rooms, UserRepository users) {
        this.rooms = rooms;
        this.users = users;
    }

    @PostMapping
    public RoomService.RoomView create(@Valid @RequestBody CreateRoomRequest request, Authentication auth) {
        return rooms.createRoom(currentUserId(auth), request.name(),
                new GameService.GameOptions(Boolean.TRUE.equals(request.bidding()), request.undoPolicy(),
                        request.leechTimerSeconds()));
    }

    @GetMapping
    public List<RoomService.RoomView> list() {
        return rooms.listWaiting();
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
