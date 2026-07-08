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
 * STOMP 구독 가드 — /topic/game/{id}는 게임 참가자만, 그 외 토픽은 인증만 요구.
 * (관전 모드가 생기면 게임 토픽 정책을 완화한다)
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
        if (destination != null && destination.startsWith("/topic/game/")) {
            UUID gameId = UUID.fromString(destination.substring("/topic/game/".length()).split("/")[0]);
            UUID userId = users.findByEmail(user.getName())
                    .map(UserAccountEntity::getId)
                    .orElseThrow(() -> new IllegalArgumentException("세션 계정 없음"));
            if (players.findById(new GamePlayerEntity.Key(gameId, userId)).isEmpty()) {
                throw new IllegalArgumentException("게임 참가자만 구독할 수 있습니다");
            }
        }
        return message;
    }
}
