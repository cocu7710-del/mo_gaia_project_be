package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserSettingsEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserSettingsRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 개인 설정 — 리치 자동 응답 범위 (CLAUDE.md 속도 완화책 ②) */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public record SettingsView(int leechAutoAcceptMaxVp, Integer leechAutoDeclineMinVp) {}

    public record UpdateRequest(@Min(0) @Max(10) int leechAutoAcceptMaxVp,
                                @Min(1) @Max(20) Integer leechAutoDeclineMinVp) {}

    private final UserSettingsRepository settings;
    private final UserRepository users;

    public SettingsController(UserSettingsRepository settings, UserRepository users) {
        this.settings = settings;
        this.users = users;
    }

    @GetMapping
    public SettingsView get(Authentication auth) {
        return settings.findById(currentUserId(auth))
                .map(s -> new SettingsView(s.getLeechAutoAcceptMaxVp(), s.getLeechAutoDeclineMinVp()))
                .orElse(new SettingsView(0, null));
    }

    @PutMapping
    public SettingsView update(@Valid @RequestBody UpdateRequest request, Authentication auth) {
        if (request.leechAutoDeclineMinVp() != null
                && request.leechAutoDeclineMinVp() <= request.leechAutoAcceptMaxVp()) {
            throw new IllegalArgumentException("자동 거절 하한은 자동 수락 상한보다 커야 합니다");
        }
        UUID userId = currentUserId(auth);
        UserSettingsEntity entity = settings.findById(userId).orElseGet(() ->
                UserSettingsEntity.builder().userId(userId).uiPrefs("{}").build());
        entity.setLeechAutoAcceptMaxVp(request.leechAutoAcceptMaxVp());
        entity.setLeechAutoDeclineMinVp(request.leechAutoDeclineMinVp());
        UserSettingsEntity saved = settings.save(entity);
        return new SettingsView(saved.getLeechAutoAcceptMaxVp(), saved.getLeechAutoDeclineMinVp());
    }

    private UUID currentUserId(Authentication auth) {
        return users.findByEmail(auth.getName())
                .map(UserAccountEntity::getId)
                .orElseThrow(() -> new IllegalStateException("세션 계정 없음"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<java.util.Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("error", e.getMessage()));
    }
}
