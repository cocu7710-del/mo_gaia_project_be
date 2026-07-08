package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.infra.jpa.GameChatEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePlayerEntity;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.UserAccountEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameChatRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePlayerRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GameRepository;
import com.gaiaproject.mo_gaia_project_be.infra.repo.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 게임/방 채팅 — 방(WAITING)과 진행 게임 모두 같은 채널. 참가자만 발신, /topic/game/{id}/chat 브로드캐스트. */
@Service
public class ChatService {

    public record ChatView(long seq, UUID userId, String nickname, String message, OffsetDateTime createdAt) {}

    private final GameRepository games;
    private final GamePlayerRepository players;
    private final GameChatRepository chats;
    private final UserRepository users;
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public ChatService(GameRepository games, GamePlayerRepository players, GameChatRepository chats,
                       UserRepository users, ObjectProvider<SimpMessagingTemplate> messaging) {
        this.games = games;
        this.players = players;
        this.chats = chats;
        this.users = users;
        this.messaging = messaging;
    }

    @Transactional
    public ChatView send(UUID gameId, UUID userId, String message) {
        games.findByIdForUpdate(gameId) // 게임 락으로 seq 직렬화
                .orElseThrow(() -> new IllegalArgumentException("게임 없음: " + gameId));
        if (players.findById(new GamePlayerEntity.Key(gameId, userId)).isEmpty()) {
            throw new IllegalStateException("게임 참가자만 채팅할 수 있습니다");
        }
        long seq = chats.findFirstByGameIdOrderBySeqDesc(gameId).map(c -> c.getSeq() + 1).orElse(1L);
        chats.save(GameChatEntity.builder()
                .gameId(gameId).seq(seq).userId(userId).message(message)
                .build());
        // created_at은 DB 기본값 — 응답·브로드캐스트에는 서버 시각 사용
        String nickname = users.findById(userId).map(UserAccountEntity::getNickname).orElse("?");
        ChatView view = new ChatView(seq, userId, nickname, message, OffsetDateTime.now());
        broadcastAfterCommit(gameId, view);
        return view;
    }

    @Transactional(readOnly = true)
    public List<ChatView> history(UUID gameId, long afterSeq) {
        return chats.findTop100ByGameIdAndSeqGreaterThanOrderBySeq(gameId, afterSeq)
                .stream().map(this::toView).toList();
    }

    private ChatView toView(GameChatEntity chat) {
        String nickname = users.findById(chat.getUserId())
                .map(UserAccountEntity::getNickname).orElse("?");
        return new ChatView(chat.getSeq(), chat.getUserId(), nickname, chat.getMessage(), chat.getCreatedAt());
    }

    private void broadcastAfterCommit(UUID gameId, ChatView view) {
        SimpMessagingTemplate template = messaging.getIfAvailable();
        if (template == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                template.convertAndSend("/topic/game/" + gameId + "/chat", (Object) view);
            }
        });
    }
}
