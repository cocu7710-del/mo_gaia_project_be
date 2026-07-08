# 맵 데이터 (v1 추출)

> v1 코드에서 추출한 원본 데이터. 출처: BE `domain/enumtype/map`, `service/MapService.java`, `util/HexUtil.java`, FE `HexMap.tsx` 등.
> ⚠️ 표시가 있는 항목은 공식 룰과 다르거나 명명이 혼란스러워 재설계 시 검수 필요.

## 1. 행성 타입

| enum | 한글명(v1) | 분류 | 비고 |
|---|---|---|---|
| TERRA | 지구 | 기본 행성 | |
| DESERT | 사막 | 기본 행성 | |
| SWAMP | 늪지 | 기본 행성 | |
| OXIDE | 산화물 | 기본 행성 | |
| VOLCANIC | 화산 | 기본 행성 | |
| TITANIUM | 티타늄 | 기본 행성 | |
| ICE | 얼음 | 기본 행성 | |
| GAIA | 가이아 | 기본 행성 | |
| TRANSDIM | **차원 변형 (보라)** ✅확정 | 기본 행성 | 가이아포머 대상. "잃어버린 행성" 명칭 사용 금지 |
| LOST_PLANET | **초월 차원** ✅확정 | 확장 행성 (딥 섹터) | enum 이름 오해 소지 — 새 프로젝트에서 이름 변경 검토 |
| BLACK_PLANET | **검은행성** ✅확정 | 확장 행성 | 항법 5단계 보상으로 배치 |
| ASTEROIDS | 소행성 | 확장 행성 (딥 섹터) | |
| EMPTY | 없음 | 우주 구역 | |

### 테라포밍 링 (순환)

```
TERRA → VOLCANIC → OXIDE → DESERT → SWAMP → TITANIUM → ICE → (TERRA)
```

- 거리 = 링 상 양방향 최단거리
- 삽당 광석: 테라포밍 연구 레벨 0~1 → 3, 레벨 2 → 2, 레벨 3~5 → 1
- LOST_PLANET / BLACK_PLANET 개척: 종족 무관 항상 3삽
- ASTEROIDS 개척: 테라포밍 광석 0 (가이아포머 소각 경로)
- 소행성/LOST_PLANET 홈 종족: 링 행성은 항상 1삽

### FE 색상 (참고)

TERRA `#1a5fa8` · VOLCANIC `#ff2222` · OXIDE `#ff6600` · DESERT `#ffcc00` · SWAMP `#8b5e3c` · TITANIUM `#555555` · ICE `#b8d4e3` · GAIA `#27ae60` · TRANSDIM `#000000` · ASTEROIDS `#f18fb0` · LOST_PLANET `#00c9a0` · BLACK_PLANET `#1a1a2e` · EMPTY `#34495e`

## 2. 섹터 레이아웃

### 기본 섹터 (SECTOR_1 ~ 10, 각 19헥스)

공통 로컬 axial 좌표 19개 (반지름 2 헥사곤):
`(0,-2)(1,-2)(2,-2)(-1,-1)(0,-1)(1,-1)(2,-1)(-2,0)(-1,0)(0,0)(1,0)(2,0)(-2,1)(-1,1)(0,1)(1,1)(-2,2)(-1,2)(0,2)`

EMPTY 제외 행성 배치 (로컬 q,r):

| 섹터 | 행성 배치 |
|---|---|
| SECTOR_1 | SWAMP(-1,0), TERRA(1,-1), DESERT(-2,1), TRANSDIM(2,-1), VOLCANIC(0,2), OXIDE(1,1) |
| SECTOR_2 | TITANIUM(0,-2), OXIDE(-1,-1), ICE(1,-1), SWAMP(-1,1), DESERT(2,-1), VOLCANIC(-1,2), TRANSDIM(1,1) |
| SECTOR_3 | TRANSDIM(0,-2), GAIA(-1,0), ICE(1,0), TITANIUM(2,-1), TERRA(-1,2), DESERT(0,2) |
| SECTOR_4 | TITANIUM(0,-2), VOLCANIC(0,-1), ICE(-2,1), OXIDE(-1,1), SWAMP(1,0), TERRA(2,0) |
| SECTOR_5 | ICE(0,-2), GAIA(-1,0), TRANSDIM(2,-2), VOLCANIC(2,-1), OXIDE(-1,2), DESERT(0,2) |
| SECTOR_6 | TRANSDIM(1,-2), SWAMP(-1,0), TERRA(1,-1), GAIA(0,1), TRANSDIM(1,1), DESERT(2,0) |
| SECTOR_7 | VOLCANIC(0,-1), SWAMP(1,-2), TRANSDIM(-2,0), GAIA(-1,1), GAIA(1,0), TITANIUM(0,2) |
| SECTOR_8 | TERRA(0,-2), ICE(0,-1), TRANSDIM(2,-2), OXIDE(-1,1), TITANIUM(1,0), TRANSDIM(-1,2) |
| SECTOR_9 | OXIDE(-1,-1), TRANSDIM(1,-2), ICE(2,-2), TITANIUM(-1,1), GAIA(1,0), SWAMP(-2,2) |
| SECTOR_10 | TRANSDIM(1,-2), DESERT(-1,0), TRANSDIM(2,-2), GAIA(1,0), TERRA(-2,2), VOLCANIC(-1,2) |

⚠️ v1에는 공식 보드게임의 뒷면 섹터(5b/6b/7b) 개념이 없음 — 단일 레이아웃만 존재. 재설계 시 공식 대응 검증 필요.

### 딥 섹터 (DEEP_SECTOR_1~8, 양면, 3헥스 타일) — Lost Fleet

로컬 좌표 3개 고정: `(0,0), (1,0), (0,1)`

| 딥섹터 | FRONT: (0,0)/(1,0)/(0,1) | BACK: (0,0)/(1,0)/(0,1) |
|---|---|---|
| 1 | LOST_PLANET / EMPTY / ASTEROIDS | EMPTY / EMPTY / ASTEROIDS |
| 2 | TRANSDIM / EMPTY / LOST_PLANET | ASTEROIDS / EMPTY / EMPTY |
| 3 | TRANSDIM / ASTEROIDS / EMPTY | EMPTY / ASTEROIDS / EMPTY |
| 4 | LOST_PLANET / ASTEROIDS / EMPTY | EMPTY / ASTEROIDS / EMPTY |
| 5 | LOST_PLANET / EMPTY / EMPTY | LOST_PLANET / ASTEROIDS / EMPTY |
| 6 | EMPTY / LOST_PLANET / EMPTY | ASTEROIDS / ASTEROIDS / EMPTY |
| 7 | TRANSDIM / EMPTY / EMPTY | EMPTY / EMPTY / ASTEROIDS |
| 8 | LOST_PLANET / EMPTY / EMPTY | EMPTY / EMPTY / ASTEROIDS |

### 1헥스 타일 (10종) — Lost Fleet

| enum | tileNumber | planetType |
|---|---|---|
| SINGLE_TRANSDIM_1 | 1 | ⚠️ LOST_PLANET (이름은 TRANSDIM인데 값은 LOST_PLANET — 의도 불명) |
| SINGLE_ASTEROIDS_1~4 | 2~5 | ASTEROIDS |
| FORGOTTEN_FLEET_TF_MARS | 6 | TF_MARS (함대 우주선) |
| FORGOTTEN_FLEET_REBELLION | 7 | REBELLION (함대 우주선) |
| FORGOTTEN_FLEET_ECLIPSE | 8 | ECLIPSE (함대 우주선) |
| SINGLE_EMPTY_1 | 9 | EMPTY |
| FORGOTTEN_FLEET_TWILIGHT | 10 | TWILIGHT (함대 우주선) |

## 3. 좌표계·거리 계산

- **Axial 좌표계 (q, r)**, flat-top 헥스
- FE 픽셀 변환: `x = HEX_SIZE * 1.5 * q`, `y = HEX_SIZE * √3 * (r + q/2)` (HEX_SIZE=41)
- 거리: cube 변환 후 `(|dx|+|dy|+|dz|)/2`, 인접 = 거리 1
- 회전: cube에서 시계방향 60°/스텝 `(x,y,z) → (-z,-x,-y)`
- 글로벌 좌표 = 로컬에 회전 적용 후 섹터 offset 가산
- 인접 방향 벡터: `[1,0],[0,1],[-1,1],[-1,0],[0,-1],[1,-1]`

### 섹터 배치 글로벌 offset (4인 맵)

| 종류 | positionNo: (offsetQ, offsetR) / 기본회전 |
|---|---|
| 기본 | 1:(-4,-1) 2:(1,-5) 3:(6,-9) 4:(-5,4) 5:(0,0) 6:(5,-4) 7:(10,-8) 8:(-1,5) 9:(4,1) 10:(9,-3) — 회전 0 |
| 딥 | 11:(-2,-5)/60° 12:(3,-9)/60° 13:(9,-11)/0° 14:(12,-7)/60° 15:(7,0)/0° 16:(2,4)/0° 17:(-4,6)/60° 18:(-7,2)/0° |
| 1헥스 | 21:(-3,1) 22:(-1,-2) 23:(2,-3) 24:(4,-6) 25:(7,-7) 26:(8,-5) 27:(6,-2) 28:(3,-1) 29:(1,2) 30:(-2,3) |

## 4. 맵 생성 알고리즘 (v1)

⚠️ **4인 고정** — 2인/3인 레이아웃 없음. 새 설계에서는 인원수별 구성 필요.

1. **기본 섹터**: SECTOR_1~4 중 랜덤 2개 → 중앙(위치 5, 6). 나머지 8개 셔플 → 위치 {1,2,3,4,7,8,9,10}. 회전 {0,60,…,300} 랜덤.
2. **충돌 해소**: 위치 순서대로, 자기 행성(TRANSDIM 제외)이 타 섹터의 같은 행성 타입과 거리 ≤1이면 60°씩 회전 (섹터당 최대 6회).
3. **딥 섹터**: 8개 전부, 각각 FRONT/BACK 랜덤, 셔플 후 위치 11~18 (회전은 고정값).
4. **1헥스 타일**: 함대 4척은 인접 포지션 배제 배치 (인접 그래프: 링 1-2-…-10-1 + 간선(3,8)), 나머지 6개 랜덤.
5. **글로벌 헥스 생성**: 전체 파싱→회전+offset→저장. 함대 타일은 planet_type=EMPTY로 저장, sector_id로 식별.

- **수동 회전**: 게임 페이즈 `MAP_ROTATE`(4명 입장 후, 비딩 전)에서만 60°씩 회전 가능.

## 5. 특수 배치물 처리 (v1)

- **TRANSDIM → GAIA**: 가이아포머 배치 → 가이아 페이즈에 헥스 planet_type 자체를 GAIA로 갱신
- **우주정거장** (이비츠 PI): EMPTY 헥스, 연방 토큰 위 불가, 항법 거리 내. 건물(SPACE_STATION, 파워값 1)로 저장. 새 섹터 진출 판정 제외
- **검은행성**: 항법 5단계 보상. EMPTY 헥스에 planet_type 변경 + LOST_PLANET_MINE 건물 배치 (광산 취급: 파워 1, 연방/리치/라운드점수 대상, 광산 재고 미감소). ⚠️ 공식 룰 "Lost Planet"과 명명 교차 혼란 — 새 설계에서 용어 통일 필요
- **함대 헥스**: 실제 섹터 아님 (`SECTOR_`/`DEEP_SECTOR_` 접두사만 실제 섹터). 새 섹터 진출·섹터 카운트 제외, 연방 형성 시 경유 불가

## 6. v1 테이블 구조 (참고용 — 그대로 쓰지 않음)

| 테이블 | 필드 요약 |
|---|---|
| game_hex | PK(game_id, hex_q, hex_r), planet_type(가변), sector_id, position_no |
| game_sector_placement | PK(game_id, position_no), sector_id, rotation |
| game_single_hex_placement | PK(game_id, position_no), tile_type |
| game_building | UUID PK, game_id, player_id, hex_q, hex_r, building_type, academy_type, is_lantids_mine, has_ring |

섹터 레이아웃의 유일한 원본은 `SectorType.java` (SQL 시드 없음). FE는 레이아웃 상수 없이 BE API 조회로 렌더링.

## 확정된 사항 (2026-07-07 검수)

- **용어 확정** ([glossary.md](../glossary.md)): TRANSDIM = 차원 변형(보라), LOST_PLANET = 초월 차원, BLACK_PLANET = 검은행성(항법 5 보상). "잃어버린 행성"이라는 행성은 없음
- **4인 전용 확정** — 2/3인 맵은 지원하지 않음

## 검수 필요 목록 (미해결)

1. SINGLE_TRANSDIM_1: 이름과 planetType(초월 차원) 불일치 — 의도 확인
2. 기본 섹터 레이아웃이 공식 보드와 일치하는지 대조 검증 필요 (뒷면 섹터 부재 포함)
3. 1헥스 tileNumber 비순차(9, 10) 의도 확인
