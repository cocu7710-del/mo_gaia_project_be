package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 세션 쿠키 기반 인증 — 로그인은 /api/auth/login (JSON), 이후 요청은 JSESSIONID로 식별.
 * FE 개발 서버는 프록시로 같은 오리진을 유지한다 (배포는 정적 리소스 통합 — CLAUDE.md).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** username = email */
    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email)
                .map(u -> User.withUsername(u.getEmail()).password(u.getPasswordHash()).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("계정 없음: " + email));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository contextRepository) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // JSON API + SameSite 쿠키 — 배포 단계에서 재검토
                .securityContext(sc -> sc.securityContextRepository(contextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .anyRequest().authenticated()) // /ws 핸드셰이크도 세션 필요
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, ex) -> response.sendError(401, "로그인이 필요합니다")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
