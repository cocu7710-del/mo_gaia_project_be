package com.gaiaproject.mo_gaia_project_be.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionGuard);
    }
}
