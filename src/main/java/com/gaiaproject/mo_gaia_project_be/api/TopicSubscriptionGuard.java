package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP 구독 가드 — 관전 허용(game-spec 12-6):
 * 게임 이벤트 토픽(/topic/game/{id})은 인증만 요구(관전자 포함),
 * 채팅 토픽(/topic/game/{id}/chat)은 참가자만 (관전자는 채팅 열람 불가).
 */
@Component
public class TopicSubscriptionGuard implements ChannelInterceptor {

    private final UserRepository users;
    private final GamePlayerRepository players;

    public TopicSubscriptionGuard(UserRepository users, GamePlayerRepository players) {
        this.users = users;
        this.players = players;
    }

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
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith("/topic/game/") && destination.endsWith("/chat")) {
            UUID gameId = UUID.fromString(destination.substring("/topic/game/".length()).split("/")[0]);
            UUID userId = users.findByEmail(user.getName())
                    .map(UserAccountEntity::getId)
                    .orElseThrow(() -> new IllegalArgumentException("세션 계정 없음"));
            if (players.findById(new GamePlayerEntity.Key(gameId, userId)).isEmpty()) {
                throw new IllegalArgumentException("채팅은 게임 참가자만 구독할 수 있습니다");
            }
        }
        return message;
    }
}
