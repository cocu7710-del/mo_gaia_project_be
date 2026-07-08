# 도메인 모델 · 테이블 설계

> PostgreSQL + Spring Boot. 아키텍처 원칙(CLAUDE.md)과 결정 연쇄 사양(rules/decision-flows.md)을 구현하는 데이터 구조.

## 1. 설계 원칙 — v1과의 결정적 차이

| | v1 | 새 설계 |
|---|---|---|
| 게임 상태 | 20여 개 정규화 테이블에 분산, FE가 계산해 저장하는 우회 경로 존재 | **이벤트 소싱**: 진실은 이벤트 로그, 상태는 스냅샷+재생으로 재구성 |
| 룰 검증 | 경로마다 다름 (일부 무검증) | 모든 변경이 단일 엔진 경로 통과 (서버 권위) |
| 대기 중 결정 | 임시 플래그·followUp 문자열 | 상태의 일부인 **결정 스택** |
| 게임 데이터 (종족·타일·섹터) | Java enum + FE 상수 중복 | **버전 있는 JSON 리소스 단일 소스** (BE가 로드, FE에 API로 제공) |

정규화 테이블을 게임 상태에 쓰지 않는 이유: 상태는 항상 서버 메모리의 엔진 객체로 다루고, DB는 (a) 복구·감사용 이벤트 로그, (b) 빠른 로드용 스냅샷만 책임진다. 턴제 4인 게임에서 상태를 SQL로 부분 질의할 일이 없고, 언두·리플레이·버그 재현이 전부 로그 재생으로 통일된다.

## 2. 테이블 목록

### 2-1. 계정

```sql
users (
  id            UUID PK,
  email         VARCHAR UNIQUE NOT NULL,
  password_hash VARCHAR NOT NULL,
  nickname      VARCHAR UNIQUE NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
)

user_settings (
  user_id                 UUID PK FK->users,
  leech_auto_accept_max_vp INT NOT NULL DEFAULT 0,   -- 승점 비용 X 이하 자동 수락
  leech_auto_decline_min_vp INT NULL,                -- Y 이상 자동 거절 (null=없음)
  ui_prefs                JSONB NOT NULL DEFAULT '{}'
)
```

### 2-2. 게임 (방 = 게임, 4인 고정)

```sql
game (
  id             UUID PK,
  name           VARCHAR NOT NULL,
  status         VARCHAR NOT NULL,  -- WAITING / SETUP / PLAYING / FINISHED / ABORTED
  ruleset_version VARCHAR NOT NULL, -- 게임 데이터 JSON 리소스 버전 (재생 호환성 보장)
  options        JSONB NOT NULL DEFAULT '{}',
    -- { undoPolicy: FREE|CONSENT|NONE, leechTimerSec, decisionTimerSec, ... }
  rng_seed       BIGINT NOT NULL,   -- 셋업 랜덤 재현용 (재생 결정성)
  last_seq       BIGINT NOT NULL DEFAULT 0,  -- 마지막 이벤트 번호 = 상태 버전
  created_by     UUID FK->users,
  created_at     TIMESTAMPTZ NOT NULL,
  finished_at    TIMESTAMPTZ NULL
)

game_player (
  game_id      UUID FK->game,
  user_id      UUID FK->users,
  seat_no      SMALLINT NOT NULL,          -- 1~4 (비딩 확정 후 턴 순서)
  faction      VARCHAR NULL,               -- 비딩 완료 시 확정
  bid_vp       SMALLINT NOT NULL DEFAULT 0,
  final_score  INT NULL,
  final_rank   SMALLINT NULL,
  PRIMARY KEY (game_id, user_id),
  UNIQUE (game_id, seat_no)
)
```

### 2-3. 이벤트 로그 (진실의 원천)

```sql
game_event (
  game_id     UUID NOT NULL FK->game,
  seq         BIGINT NOT NULL,            -- 1부터 단조 증가
  event_type  VARCHAR NOT NULL,           -- §4 이벤트 타입 카탈로그
  actor       UUID NULL,                  -- 결정 주체 (시스템 이벤트는 null)
  payload     JSONB NOT NULL,             -- 결정 페이로드 + 엔진이 적용한 효과 요약
  undone_by   BIGINT NULL,                -- 언두된 이벤트: 무효화시킨 UNDO 이벤트의 seq
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (game_id, seq)
)
```

- **append-only.** 언두도 삭제가 아니라 `TURN_UNDONE` 이벤트 추가 + 대상 구간의 `undone_by` 마킹 (재생기는 마킹된 이벤트를 건너뜀). 분쟁·디버깅 시 "무엇이 취소됐는지"까지 남는다.
- 랜덤이 필요한 이벤트(맵 생성, 타일 셔플)는 **뽑기 결과 자체를 payload에 기록** — 재생 시 재추첨하지 않음.

### 2-4. 스냅샷 (빠른 로드 + 언두 목표점)

```sql
game_snapshot (
  game_id       UUID NOT NULL FK->game,
  seq           BIGINT NOT NULL,          -- 이 스냅샷이 반영한 마지막 이벤트 seq
  snapshot_type VARCHAR NOT NULL,         -- TURN_START / ROUND_START / SETUP_DONE
  state         JSONB NOT NULL,           -- §3 GameState 문서 전체
  created_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (game_id, seq)
)
```

- 게임 로드 = 최신 스냅샷 + 이후 이벤트 재생 (수십 개 이하 — 밀리초 단위)
- 턴 초기화 = 해당 TURN_START 스냅샷으로 복원 + 구간 `undone_by` 마킹
- 보존 정책: 진행 중엔 전부, 종료 후엔 ROUND_START만 남기고 정리 가능

### 2-5. 대기 결정 프로젝션 (조회·타이머용)

```sql
game_pending_decision (
  game_id       UUID NOT NULL FK->game,
  decision_id   UUID PK,
  stack_order   INT NOT NULL,             -- 스택 위치 (해소 순서)
  target_player UUID NOT NULL,
  decision_type VARCHAR NOT NULL,         -- decision-flows.md §1 카탈로그
  context       JSONB NOT NULL,           -- 선택지 계산에 필요한 컨텍스트
  deadline_at   TIMESTAMPTZ NULL,         -- 방 타이머
  created_seq   BIGINT NOT NULL
)
```

- **진실은 GameState 안의 결정 스택** — 이 테이블은 같은 트랜잭션에서 유지되는 프로젝션이다. 용도: "내 입력 대기 중인 게임" 목록 조회, 타이머 스케줄러, 푸시 알림. 불일치 시 GameState 기준으로 재구축 가능.

### 2-6. 부가

```sql
game_chat (game_id, seq, user_id, message, created_at)
```

## 3. GameState JSONB 스키마 (엔진 메모리 모델 = 스냅샷 형식)

```jsonc
{
  "version": 1234,                // = 반영된 마지막 이벤트 seq
  "phase": "PLAYING",             // SETUP_BID / SETUP_MINES / SETUP_BOOSTER / PLAYING / GAIA_PHASE / INCOME / FINISHED
  "round": 3,
  "turnOrder": ["p1","p2","p3","p4"],   // 이번 라운드 (전 라운드 패스 순)
  "activePlayer": "p2",
  "passed": ["p1"],

  "decisionStack": [              // 최상단이 현재 대기 결정
    { "id":"d-77", "type":"LEECH_RESPONSE", "target":"p3",
      "context": { "batch":"b-9", "amount":2, "vpCost":1 } }
  ],

  "map": {
    "sectors": [ { "positionNo":5, "sectorId":"SECTOR_3", "rotation":120 } ],
    "singleHexTiles": [ { "positionNo":23, "tileType":"FORGOTTEN_FLEET_ECLIPSE" } ],
    "hexes": { "q,r": { "planet":"GAIA", "sectorId":"SECTOR_3" } },   // 가변 상태만 (가이아 변환·검은행성)
    "buildings": { "q,r": { "owner":"p1", "type":"MINE", "lantidsParasite":false, "ring":false } }
  },

  "players": {
    "p1": {
      "faction": "TAKLONS", "vp": 24,
      "resources": { "credits":10, "ore":4, "knowledge":3, "qic":1 },
      "power": { "bowl1":2, "bowl2":3, "bowl3":1, "gaia":0, "brainstone":"BOWL2" },
      "tracks": { "terraforming":2, "navigation":1, "ai":0, "gaiaforming":0, "economy":2, "science":1 },
      "techTiles": [ { "code":"BASIC_TILE_4", "coveredBy":"ADV_TILE_3" } ],
      "federations": [ { "tile":"FED_TILE_2", "flipped":false } ],
      "federationGroup": { "buildings":["q,r"], "satellites":["q,r"], "power":9 },
      "booster": "BOOSTER_7", "boosterActionUsed": false,
      "stock": { "mine":5, "tradingStation":3, "researchLab":2, "pi":1, "academyK":1, "academyQ":1, "gaiaformer":1 },
      "fleetProbes": ["TF_MARS"], "artifacts": [],
      "roundFlags": { "factionAbilityUsed": false },
      "buffs": []                 // 즉시 사용 확정으로 이월 버프 없음 — 예약 필드
    }
  },

  "board": {
    "roundScoringTiles": ["ROUND_TILE_MINE", "..."],   // 6개 (라운드 1~6)
    "finalScoringTiles": ["FINAL_TILE_SATELLITES", "FINAL_TILE_PLANET_TYPES"],
    "techOffers":   [ { "position":1, "track":"TERRA_FORMING", "tile":"BASIC_TILE_2" } ],
    "advTechOffers":[ { "position":1, "track":"TERRA_FORMING", "tile":"ADV_TILE_10", "takenBy":null } ],
    "commonAdvCondition": "VP_25",
    "economyTrackOption": "OPTION_A",
    "federationSupply": [ { "tile":"FED_TILE_1", "count":3, "position":null } ],
    "boosterSupply": [ { "code":"BOOSTER_2", "heldBy":null } ],
    "powerActionsUsed": ["PWR_ORE"],                    // 이번 라운드
    "fleetActionsUsed": [],
    "artifactOffers": [ { "code":"ARTIFACT_6", "position":1, "takenBy":null } ],
    "trackLevel5Occupied": { "navigation":"p3" },
    "leechBatches": [ { "id":"b-9", "trigger":"p2", "hex":"q,r", "offers":[ "..." ] } ]
  }
}
```

## 4. 이벤트 타입 카탈로그 (event_type)

| 분류 | 타입 |
|---|---|
| 셋업 | GAME_CREATED, PLAYER_JOINED, MAP_GENERATED(뽑기 결과 포함), BID_PLACED, BID_PASSED, FACTION_SELECTED, INITIAL_MINE_PLACED, BOOSTER_PICKED |
| 메인 액션 | ACTION_MINE_BUILT, ACTION_GAIAFORMER_DEPLOYED, ACTION_UPGRADED, ACTION_FEDERATION_FORMED, ACTION_RESEARCH_ADVANCED, ACTION_POWER_ACTION, ACTION_SPECIAL_ACTION, ACTION_PASSED, ACTION_FLEET_ENTERED, FREE_ACTION_CONVERTED |
| 결정 해소 | DECISION_RESOLVED (payload: decision_id, type, 선택 내용, 적용 효과) — 리치 응답·타일 선택·칸 선택 등 스택 해소 전부 |
| 페이즈 | ROUND_STARTED, INCOME_APPLIED, GAIA_PHASE_PROCESSED, ROUND_ENDED, GAME_FINISHED(최종 점수 명세) |
| 관리 | TURN_UNDONE(무효 구간 명시), PLAYER_TIMEOUT_APPLIED, UNDO_REQUESTED, UNDO_CONSENTED |

모든 이벤트 payload에는 엔진이 적용한 **효과 요약**을 함께 기록 — 재생 없이 로그만 읽어도 감사 가능. 자원은 **변경 전/후 값**까지 기록한다:

```jsonc
// game_event.payload 표준 형식
{
  "input": { "hexQ": 2, "hexR": -1, "terraformPayment": "ORE" },   // 제출된 결정 내용
  "effects": {
    "resources": {          // 영향받은 플레이어만, 변경된 항목만 (from → to)
      "p1": { "ore": { "from": 4, "to": 2 }, "credits": { "from": 10, "to": 8 } },
      "p3": { "vp": { "from": 24, "to": 23 }, "power": { "bowl1": {"from":2,"to":0}, "bowl2": {"from":1,"to":3} } }
    },
    "vpLog": [ { "player": "p3", "delta": -1, "category": "LEECH_COST" } ],  // VP는 사유 카테고리 필수
    "board": [ "MINE_PLACED q,r", "ROUND_SCORE +2 (ROUND_TILE_MINE)" ],      // 사람이 읽는 요약
    "pushedDecisions": [ { "id": "d-78", "type": "LEECH_RESPONSE", "target": "p3" } ]
  }
}
```

이 형식 덕분에 게임 내 **액션 히스토리 UI**("P1이 광산 건설: 광석 -2, 크레딧 -2, P3 리치 +2파워/-1VP")를 로그 테이블 조회만으로 그릴 수 있다 — 상태 재생 불필요.

## 5. 동시성 · 트랜잭션 전략

1. 게임당 **단일 라이터**: 결정 제출 처리 시 `SELECT ... FOR UPDATE`로 game 행 잠금 (또는 `pg_advisory_xact_lock(game_id)`)
2. 제출 페이로드의 `expectedVersion(=last_seq)` ≠ 현재 `last_seq`면 **409 거부 + 최신 상태 리싱크** (낙관적 버전 검증)
3. 한 결정 해소 = 한 트랜잭션: 검증 → 엔진 적용 → game_event INSERT → last_seq 증가 → pending_decision 갱신 → (턴 경계면) 스냅샷 INSERT → 커밋 → WebSocket 브로드캐스트
4. 브로드캐스트는 커밋 후 (트랜잭션 아웃박스 없이 시작 — 유실 시 클라이언트가 버전 불일치를 감지하고 리싱크하므로 안전)

## 6. 룰 엔진 구조 (패키지 설계)

```
com.gaiaproject.mo_gaia_project_be
├─ engine/            # 순수 룰 엔진 — DB·웹 의존성 없음 (단위 테스트 대상)
│  ├─ GameState       # §3 모델 (불변 or 명시적 복제)
│  ├─ GameEngine      # apply(state, decision) → (newState, events, pushedDecisions)
│  ├─ effects/        # 효과 해소기 (건설, 리치 생성, 타일 획득, 트랙 전진 …)
│  ├─ validators/     # 결정별 검증 (단일 buildingPowerValue() 포함)
│  └─ rules/          # 게임 데이터 JSON 로더 (factions.json, sectors.json …)
├─ application/       # 트랜잭션 서비스 (락, 이벤트 저장, 스냅샷, 언두)
├─ api/               # REST (계정, 방, 상태 조회) + WebSocket (결정 제출, 브로드캐스트)
└─ infra/             # JPA 엔티티 (§2 테이블), 설정
```

- **엔진은 순수 함수**: 같은 상태 + 같은 결정 = 같은 결과. 이것이 재생·언두·테스트의 전제
- 게임 데이터는 `src/main/resources/gamedata/*.json` (버전 태그) — v1 enum에서 변환해 생성, FE도 같은 파일을 API로 받아 사전 검증에 사용 (중복 구현 제거)

## 7. WebSocket 프로토콜 (개요)

- 구독: `/topic/game/{id}` (전체 브로드캐스트), `/user/queue/game/{id}` (개인 — 리치 오퍼 등)
- 클라 → 서버: `SubmitDecision { decisionId, expectedVersion, payload }`
- 서버 → 클라: `Events { fromSeq, events[] }` (증분) / `StateSync { state }` (버전 불일치·재접속 시)
- 재접속: 최신 스냅샷 + 이후 이벤트로 즉시 복원 (decisionStack 포함 — "지금 뭘 골라야 하는지"까지 복원)

## 8. 미확정 (구현 단계에서 결정)

1. GameState 직렬화 상세 스키마 (JSON Schema로 고정, ruleset_version과 함께 관리)
2. 스냅샷 주기 최적화 (기본: 턴 시작마다 — 성능 문제 시 N턴마다로 완화)
3. 타이머 스케줄러 구현 방식 (DB 폴링 vs 인메모리 + 재기동 복구)
4. 인증 방식 상세 (세션 vs JWT — 개인 서버 규모면 세션 권장)
