package com.gaiaproject.mo_gaia_project_be.engine.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class PlayerState {
    private String faction;
    private int vp;
    private int credits;
    private int ore;
    private int knowledge;
    private int qic;
    private int bowl1;
    private int bowl2;
    private int bowl3;
    private int gaiaPower;
    /** 타클론 전용: BOWL1/BOWL2/BOWL3/GAIA, 그 외 null */
    private String brainstone;
    /** TERRA_FORMING/NAVIGATION/AI/GAIA_FORMING/ECONOMY/SCIENCE → 0~5 */
    private Map<String, Integer> tracks = new HashMap<>();
    /** MINE/TRADING_STATION/RESEARCH_LAB/PLANETARY_INSTITUTE/ACADEMY/GAIAFORMER → 남은 재고 */
    private Map<String, Integer> stock = new HashMap<>();
    private List<String> techTiles = new ArrayList<>();
    /** 고급 타일에 덮인 기본 타일 (효과 비활성) */
    private List<String> coveredTechTiles = new ArrayList<>();
    private List<String> federationTokens = new ArrayList<>();
    /** 뒤집힌(사용된) 연방 토큰 — 고급 타일·5단계 진입에 사용 불가 */
    private List<String> usedFederationTokens = new ArrayList<>();
    private String booster;
    private boolean passed;
    /** 결성한 연방 그룹: {buildings:[hexKey], satellites:[hexKey], tile:id} */
    private List<Map<String, Object>> federations = new ArrayList<>();
    /** 입장한 함대 (TF_MARS/REBELLION/ECLIPSE/TWILIGHT) */
    private List<String> fleetProbes = new ArrayList<>();
    private List<String> artifacts = new ArrayList<>();
    /** 이번 라운드 사용한 특수 액션 ("FACTION:xxx" / "BOOSTER:xxx" / "TECH_TILE:xxx") */
    private List<String> usedSpecialActions = new ArrayList<>();
    /** 발타크: QIC로 변환된 가이아포머 수 (라운드 종료 시 반환) */
    private int baltaksConvertedFormers;
    /** 연방 결성 횟수 (하이브 (n+1)×7 목표 계산용) */
    private int federationsFormedCount;
    /** 팅커로이드: 게임 중 이미 사용한 개인 액션 타일 */
    private List<String> tinkeroidsUsedTiles = new ArrayList<>();
    /** 팅커로이드: 이번 라운드 선택된 개인 액션 (미사용 시 null) */
    private String tinkeroidsCurrentAction;
    /** 종족 비딩값 — 최종 점수에서 차감 (decision-flows §4) */
    private int bidVp;

    public int track(String name) {
        return tracks.getOrDefault(name, 0);
    }

    public int stockOf(String building) {
        return stock.getOrDefault(building, 0);
    }
}
