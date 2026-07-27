package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.infra.jpa.GamePendingDecisionEntity;
import com.gaiaproject.mo_gaia_project_be.infra.repo.GamePendingDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** ③ 리치 응답 타이머 — 마감 초과 오퍼를 자동 거절한다 (방 옵션 leechTimerSeconds가 켜진 게임만 마감이 존재). */
@Component
@ConditionalOnProperty(name = "gaia.leech-sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class LeechTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(LeechTimeoutJob.class);

    private final GamePendingDecisionRepository pendingDecisions;
    private final GameService gameService;

    public LeechTimeoutJob(GamePendingDecisionRepository pendingDecisions, GameService gameService) {
        this.pendingDecisions = pendingDecisions;
        this.gameService = gameService;
    }

    @Scheduled(fixedDelay = 3000)
    public void sweep() {
        List<GamePendingDecisionEntity> expired =
                pendingDecisions.findByDecisionTypeAndDeadlineAtBefore("LEECH_RESPONSE", OffsetDateTime.now());
        for (GamePendingDecisionEntity decision : expired) {
            try {
                gameService.submit(decision.getGameId(), new GameEngine.Submit(
                        decision.getTargetPlayer(), "LEECH_RESPONSE", decision.getDecisionId(),
                        Map.of("accept", false, "auto", true, "timeout", true)), null);
            } catch (RuntimeException e) {
                // 이미 수동 응답됐거나 언두로 사라진 행 — 프로젝션이 곧 재구축되므로 무시
                log.debug("리치 타임아웃 처리 스킵 game={} decision={}: {}",
                        decision.getGameId(), decision.getDecisionId(), e.getMessage());
            }
        }
    }
}
