# 타일·점수·건물·Lost Fleet 데이터 (v1 추출)

> v1 코드에서 추출한 원본 데이터. 출처: BE `domain/enumtype/{booster,federation,rounds,building,artifact,action}`, 관련 service, FE `gameCosts.ts` 등.
> ⚠️ 표시 항목은 공식 룰과 다르거나 구현 불일치 의심 — 검수 필요 목록 참조.

## 1. 라운드 부스터 (14종 — 기본+확장 통합)

| 코드 | 수입 | 패스 점수 | 액션 |
|---|---|---|---|
| BOOSTER_1 | 광석 1, 지식 1 | — | — |
| BOOSTER_2 | 크레딧 2, QIC 1 | — | — |
| BOOSTER_3 | 광석 1, 파워 토큰 2(bowl1) | — | — |
| BOOSTER_4 | 광석 1 | 광산 1개당 1VP | — |
| BOOSTER_5 | 지식 1 | 연구소 1개당 3VP | — |
| BOOSTER_6 | 광석 1 | 교역소 1개당 2VP | — |
| BOOSTER_7 | 파워 차징 4 | PI+아카데미 1개당 4VP | — |
| BOOSTER_8 | 크레딧 4 | 가이아 행성 1개당 1VP | — |
| BOOSTER_9 | 광석 1 | 행성 종류 1개당 1VP | — |
| BOOSTER_10 | 광석 1 | 가이아포머 1개당 3VP | — |
| BOOSTER_11 | 크레딧 3 | 딥 섹터 건물 1개당 2VP (LF) | — |
| BOOSTER_12 | 파워 차징 2 | — | 가이아포머 즉시 배치(즉시 가이아 변환) |
| BOOSTER_13 | 파워 차징 2 | — | 항해 +3 |
| BOOSTER_14 | 크레딧 2 | — | 테라포밍 1단계 |

- 세팅: `인원수 + 3`개 오퍼. 초기 선택은 역순(4→1). 부스터 액션은 라운드당 1회.

## 2. 연방

### 2-1. 연방 타일 (기본 6 + 확장 8 + 글린 전용 1)

| 코드 | 즉시 보상 | 사용 가능 | 특수 |
|---|---|---|---|
| FED_TILE_1 | 지식 2 + 6VP | O | |
| FED_TILE_2 | 크레딧 6 + 7VP | O | |
| FED_TILE_3 | 12VP | X (획득 즉시 used) | |
| FED_TILE_4 | QIC 1 + 8VP | O | |
| FED_TILE_5 | 광석 2 + 7VP | O | |
| FED_TILE_6 | 파워 토큰 2(bowl1) + 8VP | O | |
| FED_EXP_TILE_1 | — | O | 기본 기술타일 1개 획득 |
| FED_EXP_TILE_2 | 지식 4 + 4VP | O | |
| FED_EXP_TILE_3 | 크레딧 8 + 8VP | O | |
| FED_EXP_TILE_4 | 광석 2 + QIC 1 + 4VP | O | |
| FED_EXP_TILE_5 | — | O | 3테라포밍 + 무료 광산 |
| FED_EXP_TILE_6 | 12VP | O | |
| FED_EXP_TILE_7 | — | O | 거리 무제한 무료 광산 |
| FED_EXP_TILE_8 | 파워 토큰 2(bowl3) + 7VP | O | |
| GLEENS_FEDERATION | 크레딧 2 + 광석 1 + 지식 1 | O | 글린 PI 건설 시 자동 지급 |

- 세팅: 기본 6종 중 1종 랜덤 → 테라포밍 트랙용(1) + 일반 공급 2. 나머지 기본은 각 3개. 확장 8종 중 4종 랜덤 → 잊힌 함대 위치 1~4에 각 1개.

### 2-2. 연방 형성 규칙 (v1 구현)

- **파워 요구치**: 기본 7+. 제노스 PI 후 6. 하이브(Ivits)는 누적 `(연방수+1)*7`
- **건물 파워값**: 광산/검은행성광산/우주정거장 1, 교역소/연구소 2, PI/아카데미 3. 보정: BASIC_TILE_9 → PI/아카데미 +1, 모웨이드 링 +2, 매드 안드로이드 PI+티타늄 행성 +1, 란티다 기생 광산 항상 1
- **위성 비용**: 파워 토큰 (bowl1→2→3 순 제거). 하이브만 QIC. EMPTY 헥스만, 함대 헥스 불가, 기존 자기 연방 헥스와 그 인접 6방향 배치 불가(하이브 예외)
- **최소 토큰 검증**: Steiner Tree 근사로 최소 위성 수 계산, 초과 사용 시 실패 (⚠️ v1 주석: 기존 MST 방식이 경로 겹침 미고려로 과다 계산하는 버그 → 교체됨)
- 하이브: 단일 연방 그룹을 계속 확장, 7의 배수 도달마다 타일 획득
- 새 건물이 기존 연방에 인접하면 자동 편입
- ⚠️ 타일 획득 차감: 트랙 보상(position=0)이 잘못 차감되는 버그 수정 흔적 있음

## 3. 라운드 점수 타일 (12종 중 6개 랜덤)

| 코드 | 트리거 | VP |
|---|---|---|
| ROUND_TILE_MINE | 광산 건설 | 2 |
| ROUND_TILE_TRADING_STATION_3 | 교역소 건설 | 3 |
| ROUND_TILE_TRADING_STATION_4 | 교역소 건설 | 4 |
| ROUND_TILE_PLANETARY_INSTITUTE → **ROUND_TILE_RESEARCH_LAB로 개명** | 연구소 건설 ✅확정 (연구소 ≠ PI. PI = 행성 의회. v1 enum명이 잘못된 것) | 4 |
| ROUND_TILE_ACADEMY | 아카데미 또는 PI 건설 | 5 |
| ROUND_TILE_GAIA_PLANET_3 | 가이아 행성 개척 | 3 |
| ROUND_TILE_GAIA_PLANET_4 | 가이아 행성 개척 | 4 |
| ROUND_TILE_TERRAFORM | 테라포밍 1삽당 (할인 전 원시 삽 수) | 2 |
| ROUND_TILE_RESEARCH_ADVANCE | 연구 1칸당 | 2 |
| ROUND_TILE_FEDERATION | 연방 결성 | 5 |
| ROUND_TILE_NEW_SECTOR | 새 섹터 진출 (LF) | 3 |
| ROUND_TILE_NEW_PLANET_TYPE | 새 행성 종류 개척 (LF) | 3 |

## 4. 최종 점수 타일 (9종 중 2개 랜덤)

| 코드 | 기준 |
|---|---|
| FINAL_TILE_ASTEROID | 소행성 건물 수 |
| FINAL_TILE_GAIA_PLANET | 가이아 행성 수 |
| FINAL_TILE_MOST_BUILDINGS | 총 건물 수 |
| FINAL_TILE_FEDERATION_BUILDINGS | 연방 소속 건물 수 (우주정거장 제외, 좌표 중복 제거) |
| FINAL_TILE_DEEP_SECTORS | 건물 있는 딥 섹터 수 |
| FINAL_TILE_PLANET_TYPES | 행성 종류 수 |
| FINAL_TILE_FEDERATION_POWER | ⚠️ 설명은 "연방 파워 토큰 총합", 구현은 위성(토큰 헥스) 개수 합 |
| FINAL_TILE_PI_ACADEMY_DISTANCE | 자기 PI–아카데미 간 최대 헥스 거리 |
| FINAL_TILE_SECTORS_WITH_BUILDINGS | 건물 있는 섹터 수 (⚠️ `SECTOR_`만 카운트, 딥 섹터 제외 — 의도 불명) |

- 순위 VP: 1위 18 / 2위 12 / 3위 6. 동점 시 해당 등수 VP 합 ÷ 인원(정수). 달성도 0이면 VP 없음
- 추가 최종 점수: 지식(과학) 트랙 3단계+ 1칸당 4VP, 남은 자원 3개당 1VP (bowl3 파워 1개=1자원, 네블라 PI 시 bowl3 2배, 브레인스톤 bowl3=2), 비딩 패널티 차감
- ⚠️ **2~3인용 중립 플레이어 룰 미구현**

## 5. 건물

| 건물 | 파워값 | 초기 재고 | 비용 (FE에만 존재 ⚠️) |
|---|---|---|---|
| 광산 | 1 | 8 | 크레딧 2 + 광석 1 (초기 배치 무료) |
| 교역소 | 2 | 4 | 광석 2 + 크레딧 6 (2거리 내 타인 건물 시 크레딧 3) |
| 연구소 | 2 | 3 | 광석 3 + 크레딧 5 |
| 행성 의회(PI) | 3 | 1 | 광석 4 + 크레딧 6 |
| 아카데미 | 3 | 2 | 광석 6 + 크레딧 6 |
| 가이아포머 | 0 (리치 없음) | 종족별 | — |
| 우주정거장 (하이브) | 1 | ⚠️ 재고 관리 미구현 | — |
| 검은행성 광산 | 1 (광산 취급) | 재고 미소모 | — |

- 업그레이드: 광산→교역소, 교역소→연구소 또는 PI, 연구소→아카데미(지식형: 지식 2 수입 / QIC형: 라운드 1회 QIC 액션). 업그레이드 시 이전 건물 재고 반환
- ⚠️ **BE에 건물 비용 검증 없음** — 비용 지불이 FE 계산 + 무검증 저장(commit-turn) 경로. 새 설계에서 서버 검증 필수 (서버 권위 원칙)

## 6. 파워 액션 / 함대 액션 (공용 보드, 라운드당 전체 1회)

### 기본 파워 액션

| 코드 | 비용 | 효과 |
|---|---|---|
| PWR_KNOWLEDGE | 파워 7 | 지식 3 |
| PWR_TERRAFORM_2 | 파워 5 | 테라포밍 2단계 |
| PWR_ORE | 파워 4 | 광석 2 |
| PWR_CREDIT | 파워 4 | 크레딧 7 |
| PWR_KNOWLEDGE_2 | 파워 4 | 지식 2 |
| PWR_TERRAFORM | 파워 3 | 테라포밍 1단계 |
| PWR_TOKEN | 파워 3 | 파워 토큰 +2 |

- 파워 소각(burn): bowl2에서 2개 제거 → bowl3에 1개 (자유 행동)
- ⚠️ 공용 QIC 액션 보드는 별도로 없음 — 함대 선박 액션으로 대체된 구조 (공식 룰과 다름 의심, 확인 필요)

### Lost Fleet 함대 선박 액션

| 코드 | 비용 | 효과 |
|---|---|---|
| TF_MARS_VP | QIC 2 | 기술타일 수 + 2 VP |
| TF_MARS_GAIAFORM | 파워 2 + 가이아포머 재고 1 | TRANSDIM 즉시 GAIA 변환 + 가이아포머 배치 |
| TF_MARS_TERRAFORM | 크레딧 3 | 다음 광산 테라포밍 1단계 무료 |
| ECLIPSE_VP | QIC 2 | 개척 행성 종류 수 + 2 VP |
| ECLIPSE_TECH | 파워 3 + 지식 2 ✅파워 3 확정 | 기술 트랙 1단계 전진 |
| ECLIPSE_MINE | 크레딧 6 | 소행성에 광산 건설 |
| REBELLION_TECH | QIC 3 | 기본 기술타일 1장 + 트랙 1칸 |
| REBELLION_UPGRADE | 파워 3 + 광석 1 | 광산→교역소 업그레이드 |
| REBELLION_CONVERT | 지식 2 | QIC 1 + 크레딧 2 |
| TWILIGHT_FED | QIC 3 | 보유 연방 토큰 보상 1회 재수령 |
| TWILIGHT_UPGRADE | 파워 3 + 광석 2 | 교역소→연구소 업그레이드 + 기술타일 |
| TWILIGHT_NAV | 지식 1 | 다음 광산 항해 +3 |
| TWILIGHT_ARTIFACT | 파워 6 소각 | 인공물 획득 |

## 7. Lost Fleet 핵심 메커니즘

### 함대 입장 (Fleet Probe)

- 비용 **VP 5** (발탁스는 VP 7). 플레이어당 최대 3개 함대, 함대당 1회
- 입장 순서 보너스: 2·3번째 파워 차징 2, 4번째 파워 차징 3
- 종족 특수: 네블라/아이타 파워 토큰 1개 영구 소각, 타클론 브레인스톤 가이아 구역 이동(불가 시 입장 불가)
- 입장하면 해당 선박의 1·2번 액션 활성화

### 인공물 (Artifact, 13종 중 4개 랜덤)

획득: TWILIGHT 함대 탐사선 + 파워 토큰 6개 영구 제거, 선착순 1인.

| 코드 | 타입 | 효과 |
|---|---|---|
| ARTIFACT_1 | 즉시 | 지식 3, QIC 1 |
| ARTIFACT_2 | 즉시 | 크레딧 5, 광석 2 |
| ARTIFACT_3 | 즉시 | 크레딧 3, 광석 3 |
| ARTIFACT_4 | 수입 | 파워 토큰 2 (bowl3) |
| ARTIFACT_5 | 수입 | 광석 1, 지식 1 |
| ARTIFACT_6 | 즉시 | 건물 있는 딥 섹터당 3VP |
| ARTIFACT_7 | 즉시 | 7VP + 소행성을 행성 종류로 간주 + 건물 수 +1 |
| ARTIFACT_8 | 즉시 | 7VP + LOST_PLANET 종류 추가 + 건물 수 +1 |
| ARTIFACT_9 | 즉시 | 과학 트랙 레벨당 3VP (⚠️ "지식 트랙" 명칭 혼용) |
| ARTIFACT_10 | 즉시 | 가이아 트랙 레벨당 3VP |
| ARTIFACT_11 | 즉시 | 레벨 3+ 트랙당 3VP |
| ARTIFACT_12 | 즉시 | 행성 유형당 1VP + 3VP |
| ARTIFACT_13 | 특수 | 연방 토큰 능력 1회 추가 발동 |

## 8. 파워 리치 v1 구현 (참고 — 새 설계는 CLAUDE.md의 블로킹 결정 방식)

- 트리거: 건물 배치/업그레이드 후, **헥스 거리 2 이내** 상대별 **최대 건물 파워값** 기준
- 실충전 가능량 제한: `chargeable = bowl1×2 + bowl2` (+브레인스톤 보정), `effective = min(건물파워, chargeable)`, 0이면 스킵
- **1파워는 자동 수령** (단 아이타·타클론 PI 보유자는 거절권 → 수동)
- VP 비용 = 실충전량 − 1. VP 부족 시 `min(보유VP+1, effective)`로 축소 오퍼
- 트리거 좌석 다음부터 시계방향, 6라운드 패스자 제외
- 타클론: +1 토큰과 차징 순서 선택(TOKEN_FIRST/CHARGE_FIRST)
- followUp 체인 필드(2삽 광산 리치, 검은행성 리치)로 연쇄 처리 — ⚠️ 새 설계에서는 결정 스택으로 대체

## 확정된 사항 (2026-07-07 검수)

- **4인 전용** — 2~3인 중립 플레이어 룰 불필요 (미구현이 문제 아님)
- **공용 QIC 액션 보드 없음이 확장판의 의도** — 함대 액션이 대체
- **"지식 트랙"(SCIENCE) 명칭 확정** ([glossary.md](../glossary.md)) — ARTIFACT_9 표기 그대로 사용
- **"연구소 건설 4VP" 라운드 타일** — 연구소는 PI(행성 의회)가 아님. 새 프로젝트에서 ROUND_TILE_RESEARCH_LAB로 개명 확정
- **ECLIPSE_TECH 비용 = 파워 3 + 지식 2** 확정

## 추가 확정 (rules/edge-cases.md 검수 완료분)

- FINAL_TILE_FEDERATION_POWER → **FINAL_TILE_SATELLITES 개명, 위성 개수 기준 확정** (하이브 QIC 위성 포함)
- FINAL_TILE_SECTORS_WITH_BUILDINGS — **딥 섹터 제외 유지 확정**
- 부스터 **14종 단일 풀 유지 확정**
- 건물 비용 서버 검증 부재 → 새 설계의 서버 권위 원칙으로 해결 (구조적)
- 하이브 연방 파워 합산 불일치 → 단일 `buildingPowerValue()` 함수 강제로 해결 (edge-cases.md §2)
