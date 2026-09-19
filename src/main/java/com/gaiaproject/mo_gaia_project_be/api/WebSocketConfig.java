package com.gaiaproject.mo_gaia_project_be.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket — 게임 이벤트 브로드캐스트 채널.
 * 구독: /topic/game/{gameId} — 결정 커밋마다 {version, events[]} 수신 (참가자만, TopicSubscriptionGuard).
 * 핸드셰이크는 세션 쿠키 인증 필요 (SecurityConfig).
 * 제출은 REST(/api/games/{id}/actions) 사용 (버전 충돌을 HTTP 409로 명확히 받기 위함).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final TopicSubscriptionGuard subscriptionGuard;

    public WebSocketConfig(TopicSubscriptionGuard subscriptionGuard) {
        this.subscriptionGuard = subscriptionGuard;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 하트비트 없으면 프록시·NAT에서 close 프레임 없이 조용히 끊긴 연결을 감지 못해 재연결이 안 된다
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10000, 10000})
                .setTaskScheduler(heartbeatTaskScheduler());
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Bean
    public TaskScheduler heartbeatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionGuard);
    }
}
