package com.gaiaproject.mo_gaia_project_be.api;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP 구독 가드 — 관전 허용(game-spec 12-6):
 * 게임 이벤트 토픽(/topic/game/{id})·채팅 토픽(/topic/game/{id}/chat) 모두 인증만 요구
 * (로그인한 누구나 구독 가능 — 관전자도 채팅 열람·수신 가능, 게임 자체가 공개 정보라 참가자 제한 없음).
 */
@Component
public class TopicSubscriptionGuard implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        Principal user = accessor.getUser();
        if (user == null) {
            throw new IllegalArgumentException("인증되지 않은 구독입니다");
        }
        return message;
    }
}
