package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(@Email @NotBlank String email,
                                @NotBlank @Size(max = 30) String nickname,
                                @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record UserView(UUID id, String email, String nickname) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;

    public AuthController(UserRepository users, PasswordEncoder encoder,
                          AuthenticationManager authManager, SecurityContextRepository contextRepository) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.contextRepository = contextRepository;
    }

    @PostMapping("/signup")
    public UserView signup(@Valid @RequestBody SignupRequest request) {
        if (users.findByEmail(request.email()).isPresent()) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다");
        }
        if (users.findByNickname(request.nickname()).isPresent()) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다");
        }
        UserAccountEntity saved = users.save(UserAccountEntity.builder()
                .email(request.email())
                .nickname(request.nickname())
                .passwordHash(encoder.encode(request.password()))
                .build());
        return new UserView(saved.getId(), saved.getEmail(), saved.getNickname());
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body,
                          HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(body.email(), body.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response); // 세션에 저장 → JSESSIONID 발급
        return me(auth);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("status", "ok");
    }

    @GetMapping("/me")
    public UserView me(Authentication auth) {
        UserAccountEntity user = users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("세션 계정 없음"));
        return new UserView(user.getId(), user.getEmail(), user.getNickname());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> badCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "이메일 또는 비밀번호가 올바르지 않습니다"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
