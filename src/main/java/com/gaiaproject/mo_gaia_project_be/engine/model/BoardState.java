package com.gaiaproject.mo_gaia_project_be.engine.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class BoardState {
    /** 라운드 1~6 점수 타일 */
    private List<String> roundScoringTiles = new ArrayList<>();
    private List<String> finalScoringTiles = new ArrayList<>();
    /** position(트랙명 또는 COMMON_1~3 / EXPANSION_1~3) → 기본 타일 id */
    private Map<String, String> techOffers = new LinkedHashMap<>();
    /** position(트랙명 또는 COMMON) → 고급 타일 id */
    private Map<String, String> advTechOffers = new LinkedHashMap<>();
    /** 고급 타일 선점: 타일 id → player id */
    private Map<String, String> advTechTakenBy = new HashMap<>();
    /** 연방 타일 공급처: 타일 id → 남은 수량 */
    private Map<String, Integer> federationSupply = new LinkedHashMap<>();
    /** 테라포밍 트랙 꼭대기 타일 */
    private String terraformTrackFedTile;
    /** 잊혀진 함대 위치(1~4) → 확장 연방 타일 id */
    private Map<String, String> fleetFedTiles = new LinkedHashMap<>();
    /** 부스터 id → 보유 중인 player id (null=공급처) */
    private Map<String, String> boosterHolders = new LinkedHashMap<>();
    private List<String> powerActionsUsedThisRound = new ArrayList<>();
    /** 트랙 5단계 점유: 트랙명 → player id */
    private Map<String, String> trackLevel5Occupied = new HashMap<>();
    /** 이번 라운드 패스 순서 — 다음 라운드 턴 순서가 된다 */
    private List<String> passOrder = new ArrayList<>();
    /** 인공물 오퍼: 인공물 id → 획득 player id (null=미획득, 선착순 1인) */
    private Map<String, String> artifactOffers = new LinkedHashMap<>();
    private String economyOption;
    private String commonAdvCondition;
    /** 셋업 초기 배치 큐: [{player, building}] */
    private List<Map<String, String>> setupQueue = new ArrayList<>();

    // ── 종족 비딩 (SETUP_BID 페이즈에서만 사용, decision-flows §4) ──
    /** 아직 종족 미배정 플레이어 (입장 순서) */
    private List<String> bidUnassigned = new ArrayList<>();
    /** 현재 경매 잔류자 (패스하지 않은 플레이어, 발언 순서) */
    private List<String> bidActive = new ArrayList<>();
    /** 현재 최고 비딩 플레이어 (경매 시작 시 null) */
    private String bidLeader;
    private int bidAmount;
    /** 선택 가능한 종족 풀 */
    private List<String> factionPool = new ArrayList<>();
}
