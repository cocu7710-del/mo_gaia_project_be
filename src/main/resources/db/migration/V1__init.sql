-- 초기 스키마 (docs/design/domain-model.md 확정본)

-- ═══════════════ 1. 계정 ═══════════════

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(30)  NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_settings (
    user_id                   UUID PRIMARY KEY REFERENCES users(id),
    leech_auto_accept_max_vp  INT   NOT NULL DEFAULT 0,
    leech_auto_decline_min_vp INT,
    ui_prefs                  JSONB NOT NULL DEFAULT '{}'
);

-- ═══════════════ 2. 게임 ═══════════════

CREATE TABLE game (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(50) NOT NULL,
    status          VARCHAR(10) NOT NULL,
    ruleset_version VARCHAR(20) NOT NULL,
    options         JSONB  NOT NULL DEFAULT '{}',
    rng_seed        BIGINT NOT NULL,
    last_seq        BIGINT NOT NULL DEFAULT 0,
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX idx_game_status ON game(status);

CREATE TABLE game_player (
    game_id     UUID NOT NULL REFERENCES game(id),
    user_id     UUID NOT NULL REFERENCES users(id),
    seat_no     SMALLINT NOT NULL,
    faction     VARCHAR(20),
    bid_vp      SMALLINT NOT NULL DEFAULT 0,
    final_score INT,
    final_rank  SMALLINT,
    PRIMARY KEY (game_id, user_id),
    UNIQUE (game_id, seat_no)
);
CREATE INDEX idx_game_player_user ON game_player(user_id);

-- ═══════════════ 3. 이벤트 로그 (append-only, 진실의 원천) ═══════════════

CREATE TABLE game_event (
    game_id    UUID   NOT NULL REFERENCES game(id),
    seq        BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor      VARCHAR(60),               -- 엔진 player id (게임 내 식별자)
    payload    JSONB  NOT NULL,   -- { input, effects: { resources(from/to), vpLog, board, pushedDecisions } }
    undone_by  BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, seq)
);

-- ═══════════════ 4. 스냅샷 ═══════════════

CREATE TABLE game_snapshot (
    game_id       UUID   NOT NULL REFERENCES game(id),
    seq           BIGINT NOT NULL,
    snapshot_type VARCHAR(15) NOT NULL,
    state         JSONB  NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, seq)
);

-- ═══════════════ 5. 대기 결정 프로젝션 ═══════════════

CREATE TABLE game_pending_decision (
    game_id       UUID NOT NULL REFERENCES game(id),
    decision_id   VARCHAR(20) NOT NULL,     -- 엔진 결정 ID ("d-1" — 카운터 기반, 게임 내 유일)
    stack_order   INT  NOT NULL,
    target_player VARCHAR(60) NOT NULL,
    decision_type VARCHAR(30) NOT NULL,
    context       JSONB NOT NULL,
    deadline_at   TIMESTAMPTZ,
    created_seq   BIGINT NOT NULL,
    PRIMARY KEY (game_id, decision_id)
);
CREATE INDEX idx_pending_target   ON game_pending_decision(target_player);
CREATE INDEX idx_pending_game     ON game_pending_decision(game_id);
CREATE INDEX idx_pending_deadline ON game_pending_decision(deadline_at) WHERE deadline_at IS NOT NULL;

-- ═══════════════ 6. 채팅 ═══════════════

CREATE TABLE game_chat (
    game_id    UUID   NOT NULL REFERENCES game(id),
    seq        BIGINT NOT NULL,
    user_id    UUID   NOT NULL REFERENCES users(id),
    message    VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, seq)
);
