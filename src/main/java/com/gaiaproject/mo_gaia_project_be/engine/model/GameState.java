package com.gaiaproject.mo_gaia_project_be.engine.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 게임 상태 문서 — 스냅샷(JSONB)으로 직렬화되는 유일한 상태 모델 (docs/design/domain-model.md §3).
 * 진행 중 모든 변경은 GameEngine.apply()를 통해서만 일어난다.
 */
@Data
public class GameState {
    private long version;
    /** SETUP_BID / SETUP_MINES / SETUP_BOOSTER / PLAYING / FINISHED */
    private String phase;
    private int round;
    private List<String> turnOrder = new ArrayList<>();
    private String activePlayer;
    /** 결정 스택 — 마지막 원소가 최상단(현재 대기 결정) */
    private List<Decision> decisionStack = new ArrayList<>();
    private int nextDecisionNo = 1;
    /** 메인 액션 진행 중 — 결정 스택이 비면 턴을 넘긴다 */
    private boolean turnEndPending;
    /** 라운드 종료 대기 — 아이타 가이아 페이즈 결정 해소 후 라운드 마감을 재개한다 (edge-cases §7) */
    private boolean roundEndPending;
    /** "q,r" → 헥스 */
    private Map<String, HexState> hexes = new LinkedHashMap<>();
    private List<Map<String, Object>> sectorPlacements = new ArrayList<>();
    private Map<String, PlayerState> players = new LinkedHashMap<>();
    private BoardState board = new BoardState();

    public PlayerState player(String id) {
        PlayerState p = players.get(id);
        if (p == null) {
            throw new IllegalArgumentException("플레이어 없음: " + id);
        }
        return p;
    }

    public Decision topDecision() {
        return decisionStack.isEmpty() ? null : decisionStack.get(decisionStack.size() - 1);
    }

    public String newDecisionId() {
        return "d-" + (nextDecisionNo++);
    }
}
