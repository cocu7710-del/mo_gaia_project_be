package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.HexCoord;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.HexState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 순수 룰 엔진 — DB·웹 의존성 없음. 같은 상태 + 같은 입력 = 같은 결과 (재생·언두·테스트의 전제).
 *
 * 지원 입력 타입은 apply()의 switch가 전체 목록이다 (셋업 배치부터 메인 액션 8종,
 * 결정 응답, 라운드 경계 결정까지 — 각 결정의 연쇄는 docs/rules/decision-flows.md).
 */
public class GameEngine {

    public record Submit(String playerId, String type, String decisionId, Map<String, Object> payload) {}

    private static final Set<String> TRACK_NAMES =
            Set.of("TERRA_FORMING", "NAVIGATION", "AI", "GAIA_FORMING", "ECONOMY", "SCIENCE");
    private static final Map<String, List<String>> UPGRADE_PATHS = Map.of(
            "MINE", List.of("TRADING_STATION"),
            "TRADING_STATION", List.of("RESEARCH_LAB", "PLANETARY_INSTITUTE"),
            "RESEARCH_LAB", List.of("ACADEMY"));
    /** 매드 안드로이드: 교역소 → 연구소/아카데미, 연구소 → 의회 (스왑) */
    private static final Map<String, List<String>> BESCODS_UPGRADE_PATHS = Map.of(
            "MINE", List.of("TRADING_STATION"),
            "TRADING_STATION", List.of("RESEARCH_LAB", "ACADEMY"),
            "RESEARCH_LAB", List.of("PLANETARY_INSTITUTE"));

    private final GameData data;

    public GameEngine(GameData data) {
        this.data = data;
    }

    /** 입력 하나를 검증·적용하고 발생 이벤트를 반환한다. 검증 실패 시 EngineException (상태 불변). */
    public List<EngineEvent> apply(GameState state, Submit submit) {
        List<EngineEvent> events = switch (submit.type()) {
            case "BID_FACTION" -> applyBidFaction(state, submit);
            case "CHOOSE_FACTION" -> applyChooseFaction(state, submit);
            case "PLACE_INITIAL_MINE" -> applyInitialPlacement(state, submit);
            case "CHOOSE_BOOSTER" -> applyChooseBooster(state, submit);
            case "ACTION_BUILD_MINE" -> applyBuildMine(state, submit);
            case "ACTION_UPGRADE" -> applyUpgrade(state, submit);
            case "ACTION_RESEARCH" -> applyResearch(state, submit);
            case "ACTION_POWER" -> applyPowerAction(state, submit);
            case "ACTION_GAIAFORM" -> applyGaiaform(state, submit);
            case "ACTION_FORM_FEDERATION" -> applyFormFederation(state, submit);
            case "ACTION_FLEET_ENTER" -> applyFleetEnter(state, submit);
            case "ACTION_FLEET" -> applyFleetAction(state, submit);
            case "ACTION_FREE" -> applyFreeAction(state, submit);
            case "ACTION_SPECIAL" -> applySpecialAction(state, submit);
            case "ACTION_PASS" -> applyPass(state, submit);
            case "END_TURN" -> applyEndTurn(state, submit);
            case "CHOOSE_TECH_TILE" -> applyChooseTechTile(state, submit);
            case "CHOOSE_FEDERATION_TILE" -> applyChooseFederationTile(state, submit);
            case "PLACE_MINE" -> applyFreeMine(state, submit);
            case "PLACE_BLACK_PLANET" -> applyPlaceBlackPlanet(state, submit);
            case "LEECH_RESPONSE" -> applyLeechResponse(state, submit);
            case "INCOME_POWER_ORDER" -> applyIncomePowerOrder(state, submit);
            case "TERRANS_GAIA_CONVERT" -> applyTerransGaiaConvert(state, submit);
            case "CHOOSE_FED_TOKEN_REUSE" -> applyFedTokenReuse(state, submit);
            case "CHOOSE_ARTIFACT" -> applyChooseArtifact(state, submit);
            case "ITARS_GAIA_TECH" -> applyItarsGaiaTech(state, submit);
            case "TINKEROIDS_ACTION_PICK" -> applyTinkeroidsPick(state, submit);
            default -> throw new EngineException("지원하지 않는 입력 타입: " + submit.type());
        };
        // 메인 액션 연쇄가 해소돼도 자동으로 턴을 넘기지 않는다 — 자유 행동 구간 유지,
        // END_TURN 제출로만 전환 (game-spec §7.10). 패스는 applyPass에서 즉시 전환.
        state.setVersion(state.getVersion() + events.size());
        return events;
    }

    // ═══════════════ 종족 비딩 (SETUP_BID, decision-flows §4) ═══════════════

    /**
     * 순차 경매: 돌아가며 값을 올리거나 패스 — 혼자 남으면 낙찰 → 종족 선택.
     * 누구든(첫 발언자 포함) 패스 가능, 최소 제시가 1. 전원 패스하면 마지막 잔류자가 비딩 0으로 낙찰.
     */
    private List<EngineEvent> applyBidFaction(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "BID_FACTION");
        List<String> active = state.getBoard().getBidActive();
        boolean pass = Boolean.TRUE.equals(submit.payload().get("pass"));
        int speakerIdx = active.indexOf(submit.playerId());

        if (pass) {
            active.remove(speakerIdx);
        } else {
            int bid = intOf(submit.payload(), "bid");
            int min = state.getBoard().getBidLeader() == null ? 1 : state.getBoard().getBidAmount() + 1;
            if (bid < min) {
                throw new EngineException("비딩값은 " + min + " 이상이어야 합니다");
            }
            state.getBoard().setBidLeader(submit.playerId());
            state.getBoard().setBidAmount(bid);
        }
        state.getDecisionStack().remove(top);

        List<Map<String, Object>> pushed = new ArrayList<>();
        if (active.size() == 1) {
            pushed.add(pushChooseFaction(state, active.get(0)));
        } else {
            int nextIdx = pass ? speakerIdx % active.size() : (speakerIdx + 1) % active.size();
            pushed.add(pushBidDecision(state, active.get(nextIdx)));
        }
        return List.of(event("BID_SUBMITTED", submit,
                Map.of("pass", pass, "amount", state.getBoard().getBidAmount(),
                        "leader", state.getBoard().getBidLeader() == null ? "" : state.getBoard().getBidLeader()),
                pushed));
    }

    private Map<String, Object> pushBidDecision(GameState state, String target) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("currentBid", state.getBoard().getBidAmount());
        context.put("leader", state.getBoard().getBidLeader());
        Decision d = new Decision(state.newDecisionId(), "BID_FACTION", target, context);
        state.getDecisionStack().add(d);
        state.setActivePlayer(target);
        return Map.of("id", d.getId(), "type", d.getType(), "target", target);
    }

    private Map<String, Object> pushChooseFaction(GameState state, String winner) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("bid", state.getBoard().getBidAmount());
        context.put("pool", new ArrayList<>(state.getBoard().getFactionPool()));
        Decision d = new Decision(state.newDecisionId(), "CHOOSE_FACTION", winner, context);
        state.getDecisionStack().add(d);
        state.setActivePlayer(winner);
        return Map.of("id", d.getId(), "type", d.getType(), "target", winner);
    }

    /**
     * 낙찰자 종족 선택 — 비딩값은 최종 점수 차감. 전원 배정 시 초기 배치 시작.
     * 모드 a: 종족에 사전 고정된 턴 슬롯(bidSlotFactions의 순번)을 함께 가져간다.
     * 모드 b(bidTurnPick): 페이로드 turnSlot(1~4)로 원하는 턴 순번 선택.
     */
    private List<EngineEvent> applyChooseFaction(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "CHOOSE_FACTION");
        String factionId = (String) submit.payload().get("faction");
        if (!state.getBoard().getFactionPool().contains(factionId)) {
            throw new EngineException("선택할 수 없는 종족입니다: " + factionId);
        }
        boolean turnPick = state.getBoard().isBidTurnPick();
        int seatNo;
        if (turnPick) {
            int slot = intOf(submit.payload(), "turnSlot");
            if (slot < 1 || slot > 4) {
                throw new EngineException("턴 순번(turnSlot)을 1~4로 지정해야 합니다");
            }
            if (state.getBoard().getBidTurnSlots().containsKey(String.valueOf(slot))) {
                throw new EngineException("이미 선택된 턴 순번입니다: " + slot);
            }
            seatNo = slot;
        } else {
            seatNo = state.getBoard().getBidSlotFactions().indexOf(factionId) + 1; // 종족↔턴 사전 고정
        }
        state.getBoard().getBidTurnSlots().put(String.valueOf(seatNo), submit.playerId());
        GameSetup.initPlayer(data, state, submit.playerId(), factionId);
        PlayerState p = state.player(submit.playerId());
        p.setBidVp((int) top.getContext().get("bid"));
        state.getBoard().getFactionPool().remove(factionId);
        state.getBoard().getBidUnassigned().remove(submit.playerId());
        state.getDecisionStack().remove(top);

        List<Map<String, Object>> pushed = new ArrayList<>();
        List<String> remaining = state.getBoard().getBidUnassigned();
        state.getBoard().getBidActive().clear();
        if (remaining.isEmpty()) {
            // 슬롯 1~4 순서로 턴 순서 확정 (모드 a·b 공통)
            for (int slot = 1; slot <= 4; slot++) {
                state.getTurnOrder().add(state.getBoard().getBidTurnSlots().get(String.valueOf(slot)));
            }
            GameSetup.startPlacementPhase(data, state);
        } else if (remaining.size() == 1) {
            // 마지막 플레이어는 경매 없이 비딩 0으로 선택
            state.getBoard().setBidLeader(remaining.get(0));
            state.getBoard().setBidAmount(0);
            state.getBoard().getBidActive().add(remaining.get(0));
            pushed.add(pushChooseFaction(state, remaining.get(0)));
        } else {
            state.getBoard().getBidActive().addAll(remaining);
            state.getBoard().setBidLeader(null);
            state.getBoard().setBidAmount(0);
            pushed.add(pushBidDecision(state, remaining.get(0)));
        }
        return List.of(event("FACTION_ASSIGNED", submit,
                Map.of("faction", factionId, "bidVp", p.getBidVp(), "seatNo", seatNo),
                pushed));
    }

    // ═══════════════ 초기 배치 ═══════════════

    private List<EngineEvent> applyInitialPlacement(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "PLACE_INITIAL_MINE");
        String building = (String) top.getContext().get("building");
        HexState hex = requireHex(state, submit);

        String home = faction(state, submit.playerId()).get("homePlanet").asText();
        if (!home.equals(hex.getPlanet())) {
            throw new EngineException("홈 행성(" + home + ")에만 초기 배치 가능: 대상은 " + hex.getPlanet());
        }
        if (hex.hasBuilding()) {
            throw new EngineException("이미 건물이 있는 헥스입니다");
        }

        decreaseStock(state.player(submit.playerId()), building);
        hex.setBuildingOwner(submit.playerId());
        hex.setBuildingType(building);

        state.getDecisionStack().remove(top);
        state.getBoard().getSetupQueue().remove(0);
        GameSetup.pushNextSetupDecision(state);

        return List.of(event("INITIAL_MINE_PLACED", submit, Map.of("building", building), List.of()));
    }

    // ═══════════════ 부스터 선택 (셋업 역순 + 패스 교체) ═══════════════

    private List<EngineEvent> applyChooseBooster(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "CHOOSE_BOOSTER");
        String booster = (String) submit.payload().get("booster");
        assignBooster(state, submit.playerId(), booster);
        state.getDecisionStack().remove(top);

        if (state.getDecisionStack().isEmpty() && "SETUP_BOOSTER".equals(state.getPhase())) {
            state.setPhase("PLAYING");
            state.setRound(1);
            incomePhase(state); // 1라운드도 수입 → (가이아 없음) → 종족 능력 순서
            pushAbilityPhase(state);
            state.setActivePlayer(state.getTurnOrder().get(0));
        } else if (!state.getDecisionStack().isEmpty()) {
            state.setActivePlayer(state.topDecision().getTarget());
        }
        return List.of(event("BOOSTER_PICKED", submit, Map.of("booster", booster), List.of()));
    }

    private void assignBooster(GameState state, String playerId, String booster) {
        Map<String, String> holders = state.getBoard().getBoosterHolders();
        if (booster == null || !holders.containsKey(booster)) {
            throw new EngineException("존재하지 않는 부스터입니다: " + booster);
        }
        if (holders.get(booster) != null) {
            throw new EngineException("다른 플레이어가 보유 중인 부스터입니다: " + booster);
        }
        PlayerState p = state.player(playerId);
        if (p.getBooster() != null) {
            holders.put(p.getBooster(), null); // 기존 부스터 반환
        }
        holders.put(booster, playerId);
        p.setBooster(booster);
    }

    // ═══════════════ 광산 건설 (메인 액션) ═══════════════

    private List<EngineEvent> applyBuildMine(GameState state, Submit submit) {
        requireMainAction(state, submit);
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);

        int qicForRange = intOf(submit.payload(), "qicForRange");
        List<Map<String, Object>> pushed =
                buildMineCore(state, submit, qicForRange, 0, false, 0, null);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_MINE_BUILT", submit,
                Map.of("resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), pushed));
    }

    /** 광산 건설 공통 (메인 액션·무료 광산 공용): 검증 → 비용 → 커밋 → 라운드 점수 → 리치 push */
    private List<Map<String, Object>> buildMineCore(GameState state, Submit submit, int qicForRange,
                                                    int freeShovels, boolean freeBuild, int rangeBonus,
                                                    String planetOnly) {
        HexState hex = requireHex(state, submit);
        HexCoord target = coordOf(submit);
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());

        String planet = hex.getPlanet();
        // 자기 가이아포머가 변환시킨 가이아 행성 → 포머 회수 후 건설 (QIC 불필요)
        boolean ownGaiaformer = "GAIA".equals(planet)
                && "GAIAFORMER".equals(hex.getBuildingType())
                && submit.playerId().equals(hex.getBuildingOwner());
        // 란티다 기생 광산: 상대가 개척한 행성에 공존 건설 (테라포밍·QIC 불필요, 기본 비용만)
        boolean parasite = hex.hasBuilding() && !ownGaiaformer
                && !submit.playerId().equals(hex.getBuildingOwner())
                && !"GAIAFORMER".equals(hex.getBuildingType())
                && hasAbility(faction, "PARASITE_MINE");
        if (parasite) {
            if (hex.getParasiteOwner() != null) {
                throw new EngineException("이미 기생 광산이 있는 헥스입니다");
            }
            if (p.stockOf("MINE") < 1) {
                throw new EngineException("광산 재고가 없습니다");
            }
            checkRange(state, submit.playerId(), target, p, qicForRange, rangeBonus);
            int parasiteCredits = freeBuild ? 0 : 2;
            int parasiteOre = freeBuild ? 0 : 1;
            requireResources(p, parasiteCredits, parasiteOre, qicForRange);
            boolean newSector = !hasPresenceInSector(state, submit.playerId(), hex.getSectorId());
            p.setCredits(p.getCredits() - parasiteCredits);
            p.setOre(p.getOre() - parasiteOre);
            p.setQic(p.getQic() - qicForRange);
            decreaseStock(p, "MINE");
            hex.setParasiteOwner(submit.playerId());
            roundScore(state, p, "MINE_PLACED", 1);
            techMineBuildVp(p, "GAIA".equals(planet));
            newColonizationTriggers(state, submit.playerId(), newSector, false); // 기생은 행성 종류 미개척
            if (hasAbility(faction, "PI_PARASITE_MINE_KNOWLEDGE_2")
                    && builtCount(state, submit.playerId(), "PLANETARY_INSTITUTE") > 0) {
                p.setKnowledge(p.getKnowledge() + 2);
            }
            autoIncorporateIntoFederation(state, submit.playerId(), target);
            return pushLeechDecisions(state, submit.playerId(), target);
        }
        if (hex.hasBuilding() && !ownGaiaformer) {
            throw new EngineException("이미 건물이 있는 헥스입니다");
        }
        if ("EMPTY".equals(planet) || "TRANSDIM".equals(planet)) {
            throw new EngineException("광산을 지을 수 없는 헥스입니다: " + planet);
        }
        if (planetOnly != null && !planetOnly.equals(planet)) {
            throw new EngineException(planetOnly + " 행성에만 건설할 수 있는 효과입니다");
        }
        // 소행성: 전 종족 공통 — 가이아포머 1개 영구 소각으로 무료 건설 (확장 종족도 홈 개념 없음)
        boolean asteroidFormerBurn = planetOnly == null && "ASTEROIDS".equals(planet);
        if (asteroidFormerBurn && p.stockOf("GAIAFORMER") < 1) {
            throw new EngineException("소행성 건설에는 소각할 가이아포머가 필요합니다");
        }
        if (p.stockOf("MINE") < 1) {
            throw new EngineException("광산 재고가 없습니다");
        }
        checkRange(state, submit.playerId(), target, p, qicForRange, rangeBonus);

        int credits = freeBuild || asteroidFormerBurn ? 0 : 2;
        int ore = freeBuild || asteroidFormerBurn ? 0 : 1;
        int qic = qicForRange;
        int rawShovels = 0;
        boolean gaia = "GAIA".equals(planet);
        if (gaia) {
            if (ownGaiaformer) {
                // 포머 회수 — 추가 비용 없음
            } else if (hasAbility(faction, "GAIA_COST_ORE_INSTEAD_QIC")) {
                ore += 1;
            } else {
                // 기본 종족 QIC 1, 확장 종족 QIC 2 — 모웨이드(GAIA_COST_QIC_1)만 1 (I-4)
                qic += faction.path("expansion").asBoolean(false)
                        && !hasAbility(faction, "GAIA_COST_QIC_1") ? 2 : 1;
            }
        } else if ("ASTEROIDS".equals(planet)) {
            rawShovels = 0; // 소행성은 테라포밍 비용 없음 (가이아포머 소각 경로 또는 함대 액션)
        } else {
            rawShovels = terraformShovels(p, faction, planet);
            int paidShovels = Math.max(0, rawShovels - freeShovels);
            ore += paidShovels * data.tech().get("shovelOreCostByLevel").get(p.track("TERRA_FORMING")).asInt();
        }
        requireResources(p, credits, ore, qic);
        boolean newSector = !hasPresenceInSector(state, submit.playerId(), hex.getSectorId());
        boolean newPlanetType = !colonizedPlanetTypes(state, submit.playerId()).contains(planet);

        p.setCredits(p.getCredits() - credits);
        p.setOre(p.getOre() - ore);
        p.setQic(p.getQic() - qic);
        decreaseStock(p, "MINE");
        if (asteroidFormerBurn) {
            decreaseStock(p, "GAIAFORMER"); // 영구 소각 — 반환 없음
        }
        if (ownGaiaformer) {
            p.getStock().merge("GAIAFORMER", 1, Integer::sum);
        }
        hex.setBuildingOwner(submit.playerId());
        hex.setBuildingType("MINE");

        roundScore(state, p, "MINE_PLACED", 1);
        techMineBuildVp(p, gaia);
        newColonizationTriggers(state, submit.playerId(), newSector, newPlanetType);
        if (rawShovels > 0) {
            roundScore(state, p, "TERRAFORM_STEP", rawShovels);
            if (hasActiveTile(p, "ADV_TILE_10")) {
                gainVp(p, 2 * rawShovels, "TECH_ADV"); // 테라포밍 삽당 +2VP (고급⑩)
            }
        }
        if (gaia) {
            roundScore(state, p, "GAIA_PLANET_COLONIZED", 1);
            if (hasAbility(faction, "GAIA_PLANET_VP_2")) {
                gainVp(p, 2, "FACTION");
            }
        }
        autoIncorporateIntoFederation(state, submit.playerId(), target);
        return pushLeechDecisions(state, submit.playerId(), target);
    }

    // ═══════════════ 업그레이드 (메인 액션) — 결정 연쇄 A3 ═══════════════

    private List<EngineEvent> applyUpgrade(GameState state, Submit submit) {
        requireMainAction(state, submit);
        HexState hex = requireHex(state, submit);
        HexCoord target = coordOf(submit);
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        String to = (String) submit.payload().get("to");

        if (!submit.playerId().equals(hex.getBuildingOwner())) {
            throw new EngineException("본인 건물이 아닙니다");
        }
        Map<String, List<String>> paths = hasAbility(faction, "SWAP_UPGRADE_PATHS")
                ? BESCODS_UPGRADE_PATHS : UPGRADE_PATHS;
        List<String> allowed = paths.get(hex.getBuildingType());
        if (allowed == null || !allowed.contains(to)) {
            throw new EngineException("업그레이드 불가: " + hex.getBuildingType() + " → " + to);
        }
        if (p.stockOf(to) < 1) {
            throw new EngineException("재고 없음: " + to);
        }

        JsonNode costNode = data.constants().get("buildings").get(to).get("cost");
        if ("TRADING_STATION".equals(to) && hasOpponentBuildingNear(state, submit.playerId(), target, 2)) {
            costNode = data.constants().get("buildings").get(to).get("costNearOpponent");
        }
        int credits = costNode.get("credits").asInt();
        int ore = costNode.get("ore").asInt();
        requireResources(p, credits, ore, 0);

        Map<String, Object> before = resourceSnapshot(p);
        p.setCredits(p.getCredits() - credits);
        p.setOre(p.getOre() - ore);
        List<Map<String, Object>> pushed = upgradeCore(state, submit, hex, target, to);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_UPGRADED", submit,
                Map.of("to", to,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), pushed));
    }

    /**
     * 업그레이드 커밋 공통 (일반 업그레이드·파이락 PI·함대 업그레이드 합류점):
     * 재고 교환 → 건물 교체 → 라운드 점수·타일 보너스 → 종족 PI 트리거 → 기술 타일·리치 push.
     * 진입부(경로 검증·비용)만 호출부가 분기한다.
     */
    private List<Map<String, Object>> upgradeCore(GameState state, Submit submit,
                                                  HexState hex, HexCoord target, String to) {
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        if (p.stockOf(to) < 1) {
            throw new EngineException("재고 없음: " + to);
        }
        p.getStock().merge(hex.getBuildingType(), 1, Integer::sum); // 이전 건물 재고 반환
        decreaseStock(p, to);
        hex.setBuildingType(to);
        if ("ACADEMY".equals(to)) {
            hex.setAcademyType((String) submit.payload().getOrDefault("academyType", "KNOWLEDGE"));
        }

        switch (to) {
            case "TRADING_STATION" -> {
                roundScore(state, p, "TRADING_STATION_BUILT", 1);
                techTsBuildVp(p);
            }
            case "RESEARCH_LAB" -> roundScore(state, p, "RESEARCH_LAB_BUILT", 1);
            case "PLANETARY_INSTITUTE", "ACADEMY" -> roundScore(state, p, "ACADEMY_OR_PI_BUILT", 1);
            default -> { }
        }

        // 종족 PI 트리거
        if ("PLANETARY_INSTITUTE".equals(to) && hasAbility(faction, "PI_GLEENS_FED_TOKEN")) {
            grantFederationTile(state, submit.playerId(), "GLEENS_FEDERATION");
        }

        List<Map<String, Object>> pushed = new ArrayList<>();
        // 기술 타일 선택은 리치 해소 "이후" 열려야 하므로 먼저 push (스택 아래쪽)
        boolean gainsTechTile = "RESEARCH_LAB".equals(to) || "ACADEMY".equals(to)
                || ("PLANETARY_INSTITUTE".equals(to) && hasAbility(faction, "PI_BUILD_GAIN_TECH_TILE"));
        if (gainsTechTile) {
            pushed.add(pushDecision(state, "CHOOSE_TECH_TILE", submit.playerId(), Map.of("reason", to)));
        }
        pushed.addAll(pushLeechDecisions(state, submit.playerId(), target));
        return pushed;
    }

    // ═══════════════ 기술 타일 선택 ═══════════════

    private List<EngineEvent> applyChooseTechTile(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "CHOOSE_TECH_TILE");
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);
        String position = (String) submit.payload().get("position");
        boolean advanced = Boolean.TRUE.equals(submit.payload().get("advanced"));
        String chosenTrack = (String) submit.payload().get("techTrack");

        state.getDecisionStack().remove(top);

        String tileId;
        if (advanced) {
            tileId = acquireAdvancedTile(state, submit, p, position);
        } else {
            tileId = acquireBasicTile(state, submit, p, position);
        }

        // 트랙 전진: 트랙 슬롯 타일 → 해당 트랙, COMMON/EXPANSION/고급 → 선택 트랙 (미지정 시 스킵)
        String advanceTarget = !advanced && TRACK_NAMES.contains(position) ? position : chosenTrack;
        if (advanceTarget != null) {
            advanceTrackIfPossible(state, submit.playerId(), advanceTarget);
        }

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("tile", tileId,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    private String acquireBasicTile(GameState state, Submit submit, PlayerState p, String position) {
        String tileId = state.getBoard().getTechOffers().get(position);
        if (tileId == null) {
            throw new EngineException("해당 위치에 기본 타일이 없습니다: " + position);
        }
        // 확장 슬롯: 연결된 함대에 입장한 플레이어만 획득 가능
        String requiredShip = state.getBoard().getExpansionTechShips().get(position);
        if (requiredShip != null && !p.getFleetProbes().contains(requiredShip)) {
            throw new EngineException("해당 함대에 입장해야 획득할 수 있는 타일입니다: " + requiredShip);
        }
        if (p.getTechTiles().contains(tileId)) {
            throw new EngineException("이미 보유한 타일입니다: " + tileId);
        }
        p.getTechTiles().add(tileId);
        applyBasicTileImmediate(state, submit, findTile(data.tech().get("basicTiles"), tileId));
        return tileId;
    }

    private String acquireAdvancedTile(GameState state, Submit submit, PlayerState p, String position) {
        String tileId = state.getBoard().getAdvTechOffers().get(position);
        if (tileId == null) {
            throw new EngineException("해당 위치에 고급 타일이 없습니다: " + position);
        }
        if (state.getBoard().getAdvTechTakenBy().containsKey(tileId)) {
            throw new EngineException("이미 선점된 고급 타일입니다: " + tileId);
        }
        if ("COMMON".equals(position)) {
            String condition = state.getBoard().getCommonAdvCondition();
            if ("VP_25".equals(condition)) {
                if (p.getVp() < 25) {
                    throw new EngineException("COMMON 고급 타일 조건 미충족: VP 25 이상 필요");
                }
            } else if (p.getFleetProbes().size() < 3) {
                throw new EngineException("COMMON 고급 타일 조건 미충족: 입장 함대 3개 이상 필요");
            }
        } else if (p.track(position) < 4) {
            throw new EngineException("고급 타일 조건 미충족: " + position + " 트랙 레벨 4 이상 필요");
        }
        flipUsableFederationToken(p);

        String covered = (String) submit.payload().get("coveredTile");
        if (covered == null) {
            throw new EngineException("덮을 기본 타일을 지정해야 합니다");
        }
        if (!p.getTechTiles().contains(covered) || p.getCoveredTechTiles().contains(covered)) {
            throw new EngineException("덮을 수 없는 타일입니다: " + covered);
        }
        p.getCoveredTechTiles().add(covered);
        p.getTechTiles().add(tileId);
        state.getBoard().getAdvTechTakenBy().put(tileId, submit.playerId());
        applyAdvancedTileImmediate(state, submit.playerId(), findTile(data.tech().get("advancedTiles"), tileId));
        return tileId;
    }

    private void applyBasicTileImmediate(GameState state, Submit submit, JsonNode tile) {
        if (!"IMMEDIATE".equals(tile.get("type").asText())) {
            return; // INCOME/PASSIVE/ACTION은 보유 효과 — 수입·패스 슬라이스에서 처리
        }
        String playerId = submit.playerId();
        if (tile.has("gain")) {
            gainResources(state, playerId, tile.get("gain"), "TECH_BASIC");
        }
        switch (tile.path("special").asText("")) {
            case "KNOWLEDGE_1_PER_PLANET_TYPE" -> {
                PlayerState p = state.player(playerId);
                p.setKnowledge(p.getKnowledge() + colonizedPlanetTypes(state, playerId).size());
            }
            case "TERRAFORM_2_PLACE_MINE" -> {
                Decision d = new Decision(state.newDecisionId(), "PLACE_MINE", playerId,
                        Map.of("freeShovels", 2, "freeBuild", true)); // 광산 비용도 무료
                state.getDecisionStack().add(d);
            }
            default -> { }
        }
    }

    private void applyAdvancedTileImmediate(GameState state, String playerId, JsonNode tile) {
        if (!"IMMEDIATE".equals(tile.get("type").asText())) {
            return;
        }
        PlayerState p = state.player(playerId);
        switch (tile.path("special").asText("")) {
            case "VP_2_PER_MINE" -> gainVp(p, 2 * mineCountForScoring(state, playerId), "TECH_ADV");
            case "VP_4_PER_TRADING_STATION" -> gainVp(p, 4 * builtCount(state, playerId, "TRADING_STATION"), "TECH_ADV");
            case "VP_5_PER_FEDERATION_TOKEN" -> gainVp(p, 5 * p.getFederationTokens().size(), "TECH_ADV");
            case "VP_2_PER_GAIA_PLANET" -> gainVp(p, 2 * gaiaPlanetCount(state, playerId), "TECH_ADV");
            case "VP_2_PER_BUILDING_IN_SECTORS" -> gainVp(p, 2 * buildingsInBaseSectors(state, playerId), "TECH_ADV");
            case "ORE_1_PER_BUILDING_IN_SECTORS" -> p.setOre(p.getOre() + buildingsInBaseSectors(state, playerId));
            case "VP_6_PER_BIG_BUILDING" -> {
                int big = builtCount(state, playerId, "PLANETARY_INSTITUTE") + builtCount(state, playerId, "ACADEMY");
                gainVp(p, 6 * big, "TECH_ADV");
            }
            case "VP_4_PER_DEEP_SECTOR_WITH_BUILDING" -> gainVp(p, 4 * deepSectorsWithBuilding(state, playerId), "TECH_ADV");
            default -> { }
        }
    }

    // ═══════════════ 트랙 전진 (타일 무료 전진 공용) ═══════════════

    /** 무료 전진 — 5단계 점유/연방 토큰 부족/발타크 항해 잠금 시 전진만 스킵 (타일은 이미 획득, edge-cases §3) */
    private void advanceTrackIfPossible(GameState state, String playerId, String track) {
        PlayerState p = state.player(playerId);
        if (navigationLocked(state, playerId, track)) {
            return; // 발타크: PI 건설 전 항해 전진 불가 (I-12)
        }
        int level = p.track(track);
        if (level >= 5) {
            return;
        }
        if (level + 1 == 5) {
            if (state.getBoard().getTrackLevel5Occupied().containsKey(track)) {
                return;
            }
            if (!hasUsableFederationToken(p)) {
                return;
            }
            flipUsableFederationToken(p);
            state.getBoard().getTrackLevel5Occupied().put(track, playerId);
        }
        applyTrackAdvance(state, playerId, track, level + 1);
    }

    /** 발타크 항해 잠금 — PI 건설 전에는 유료·무료 어떤 경로로도 항해 트랙 전진 불가 (I-12) */
    private boolean navigationLocked(GameState state, String playerId, String track) {
        return "NAVIGATION".equals(track)
                && hasAbility(data.faction(state.player(playerId).getFaction()), "NAVIGATION_LOCKED_UNTIL_PI")
                && builtCount(state, playerId, "PLANETARY_INSTITUTE") == 0;
    }

    private void applyTrackAdvance(GameState state, String playerId, String track, int newLevel) {
        PlayerState p = state.player(playerId);
        p.getTracks().put(track, newLevel);
        if (newLevel == 3) {
            chargePower(p, 3); // 공통: 2→3 진입 시 파워 3 순환
        }
        JsonNode trackNode = data.tech().get("tracks").get(track);
        JsonNode levels = trackNode.get("levels");
        if (levels != null) {
            JsonNode reward = levels.get(newLevel - 1);
            if (reward.has("gain")) {
                gainResources(state, playerId, reward.get("gain"), "TRACK");
            }
            switch (reward.path("special").asText("")) {
                case "GAIN_TRACK_TOP_FEDERATION_TILE" -> {
                    String top = state.getBoard().getTerraformTrackFedTile();
                    if (top != null) {
                        state.getBoard().setTerraformTrackFedTile(null);
                        grantFederationTile(state, playerId, top);
                    }
                }
                case "PLACE_BLACK_PLANET" -> state.getDecisionStack().add(
                        new Decision(state.newDecisionId(), "PLACE_BLACK_PLANET", playerId, Map.of()));
                case "VP_4_PLUS_1_PER_GAIA_PLANET" ->
                        gainVp(p, 4 + gaiaPlanetCount(state, playerId), "TRACK");
                default -> { }
            }
        } else if (newLevel == 5 && trackNode.has("level5Gain")) {
            gainResources(state, playerId, trackNode.get("level5Gain"), "TRACK"); // 수입 소멸은 수입 슬라이스에서 반영
        }
        roundScore(state, p, "RESEARCH_ADVANCED", 1);
        if (hasActiveTile(p, "ADV_TILE_18")) {
            gainVp(p, 2, "TECH_ADV"); // 연구 전진마다 +2VP (고급⑱)
        }
    }

    // ═══════════════ 연구 전진 / 파워 액션 / 가이아포밍 / 연방 / 패스 (메인 액션) ═══════════════

    private List<EngineEvent> applyResearch(GameState state, Submit submit) {
        requireMainAction(state, submit);
        String track = (String) submit.payload().get("track");
        if (track == null || !TRACK_NAMES.contains(track)) {
            throw new EngineException("트랙을 지정해야 합니다");
        }
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        if (hasAbility(faction, "NAVIGATION_LOCKED_UNTIL_PI") && "NAVIGATION".equals(track)
                && builtCount(state, submit.playerId(), "PLANETARY_INSTITUTE") == 0) {
            throw new EngineException("발타크는 의회 건설 전까지 항해 트랙 전진 불가");
        }
        int level = p.track(track);
        if (level >= 5) {
            throw new EngineException("트랙 최대 레벨입니다");
        }
        if (level + 1 == 5) {
            if (state.getBoard().getTrackLevel5Occupied().containsKey(track)) {
                throw new EngineException("5단계가 이미 점유된 트랙입니다");
            }
            flipUsableFederationToken(p);
            state.getBoard().getTrackLevel5Occupied().put(track, submit.playerId());
        }
        int cost = data.tech().get("advanceCost").get("knowledge").asInt();
        if (p.getKnowledge() < cost) {
            throw new EngineException("지식 부족 (필요: " + cost + ")");
        }
        Map<String, Object> before = resourceSnapshot(p);
        p.setKnowledge(p.getKnowledge() - cost);
        applyTrackAdvance(state, submit.playerId(), track, level + 1);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_RESEARCH_ADVANCED", submit,
                Map.of("track", track,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    private List<EngineEvent> applyPowerAction(GameState state, Submit submit) {
        requireMainAction(state, submit);
        String actionId = (String) submit.payload().get("actionId");
        if (state.getBoard().getPowerActionsUsedThisRound().contains(actionId)) {
            throw new EngineException("이번 라운드에 이미 사용된 액션입니다: " + actionId);
        }
        JsonNode action = findTile(data.actions().get("powerActions"), actionId);
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);

        spendPower(p, action.get("cost").get("power").asInt(),
                Boolean.TRUE.equals(submit.payload().get("useBrainstone")),
                nevlasPiDouble(state, submit.playerId()));
        if (action.has("gain")) {
            gainResources(state, submit.playerId(), action.get("gain"), "POWER_ACTION");
        }
        List<Map<String, Object>> pushed = new ArrayList<>();
        switch (action.path("special").asText("")) {
            case "BUILD_MINE_TERRAFORM_1_FREE" -> pushed.add(pushFreeMine(state, submit.playerId(), 1, false, 0, null));
            case "BUILD_MINE_TERRAFORM_2_FREE" -> pushed.add(pushFreeMine(state, submit.playerId(), 2, false, 0, null));
            default -> { }
        }
        state.getBoard().getPowerActionsUsedThisRound().add(actionId);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_POWER_ACTION", submit,
                Map.of("actionId", actionId,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), pushed));
    }

    private List<EngineEvent> applyGaiaform(GameState state, Submit submit) {
        requireMainAction(state, submit);
        HexState hex = requireHex(state, submit);
        HexCoord target = coordOf(submit);
        PlayerState p = state.player(submit.playerId());

        if (!"TRANSDIM".equals(hex.getPlanet())) {
            throw new EngineException("차원 변형 행성에만 가이아포머를 배치할 수 있습니다");
        }
        if (hex.hasBuilding()) {
            throw new EngineException("이미 건물이 있는 헥스입니다");
        }
        int gaiaLevel = p.track("GAIA_FORMING");
        if (gaiaLevel < 1) {
            throw new EngineException("가이아 트랙 레벨 1 이상이 필요합니다");
        }
        if (p.stockOf("GAIAFORMER") < 1) {
            throw new EngineException("사용 가능한 가이아포머가 없습니다");
        }
        int qicForRange = intOf(submit.payload(), "qicForRange");
        checkRange(state, submit.playerId(), target, p, qicForRange, 0);
        requireResources(p, 0, 0, qicForRange);

        Map<String, Object> before = resourceSnapshot(p);
        p.setQic(p.getQic() - qicForRange);
        int cost = data.tech().get("gaiaformCostByLevel").get(gaiaLevel).asInt();
        moveTokensToGaia(p, cost);
        decreaseStock(p, "GAIAFORMER");
        hex.setBuildingOwner(submit.playerId());
        hex.setBuildingType("GAIAFORMER");

        state.setTurnEndPending(true);
        return List.of(event("ACTION_GAIAFORMER_DEPLOYED", submit,
                Map.of("powerToGaia", cost,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    @SuppressWarnings("unchecked")
    private List<EngineEvent> applyFormFederation(GameState state, Submit submit) {
        requireMainAction(state, submit);
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        boolean ivits = hasAbility(faction, "SINGLE_FEDERATION_CUMULATIVE");
        List<String> buildings = (List<String>) submit.payload().get("buildings");
        List<String> satellites = (List<String>) submit.payload().getOrDefault("satellites", List.of());
        if (buildings == null || buildings.isEmpty()) {
            throw new EngineException("연방에 포함할 건물을 지정해야 합니다");
        }

        for (String key : buildings) {
            HexState h = state.getHexes().get(key);
            if (h == null || (!submit.playerId().equals(h.getBuildingOwner())
                    && !submit.playerId().equals(h.getParasiteOwner()))) {
                throw new EngineException("본인 건물이 아닙니다: " + key);
            }
        }
        for (String key : satellites) {
            HexState h = state.getHexes().get(key);
            // 빈 우주(우주정거장 위 허용) — 다른 플레이어 위성과는 공존 가능, 내 위성 중복만 불가
            if (h == null || !"EMPTY".equals(h.getPlanet()) || h.getShip() != null
                    || (h.hasBuilding() && !"SPACE_STATION".equals(h.getBuildingType()))
                    || h.getSatelliteOwners().contains(submit.playerId())) {
                throw new EngineException("위성을 배치할 수 없는 헥스입니다: " + key);
            }
        }

        // 하이브: 단일 연방 그룹 누적 확장 — 기존 그룹과 합산 (edge-cases §2)
        List<String> allBuildings = new ArrayList<>(buildings);
        List<String> allSatellites = new ArrayList<>(satellites);
        Map<String, Object> existingGroup = null;
        if (ivits && !p.getFederations().isEmpty()) {
            existingGroup = p.getFederations().get(0);
            for (String key : (List<String>) existingGroup.get("buildings")) {
                if (!allBuildings.contains(key)) {
                    allBuildings.add(key);
                }
            }
            allSatellites.addAll((List<String>) existingGroup.get("satellites"));
        }
        requireConnected(allBuildings, allSatellites);
        requireNoRedundantSatellites(allBuildings, allSatellites);
        // 일반 종족: 자기 기존 연방에 포함·인접한 헥스는 새 연방에 쓸 수 없음 (edge-cases §2 — 하이브 제외)
        if (!ivits) {
            List<String> existingKeys = new ArrayList<>();
            for (Map<String, Object> group : p.getFederations()) {
                existingKeys.addAll((List<String>) group.get("buildings"));
                existingKeys.addAll((List<String>) group.get("satellites"));
            }
            for (String key : allBuildings) {
                requireNotNearExistingFederation(key, existingKeys);
            }
            for (String key : allSatellites) {
                requireNotNearExistingFederation(key, existingKeys);
            }
        }

        int power = 0;
        for (String key : allBuildings) {
            HexState h = state.getHexes().get(key);
            power += submit.playerId().equals(h.getBuildingOwner())
                    ? buildingPowerValue(state, submit.playerId(), h)
                    : 1; // 란티다 기생 광산: 연방 파워 1
        }
        int required;
        if (ivits) {
            required = (p.getFederationsFormedCount() + 1) * 7;
        } else {
            required = data.tiles().get("federationSetup").get("basePower").asInt();
            if (hasAbility(faction, "PI_FEDERATION_POWER_6")
                    && builtCount(state, submit.playerId(), "PLANETARY_INSTITUTE") > 0) {
                required = 6;
            }
        }
        if (power < required) {
            throw new EngineException("연방 파워 부족 (" + power + " < " + required + ")");
        }

        Map<String, Object> before = resourceSnapshot(p);
        if (ivits) {
            if (p.getQic() < satellites.size()) {
                throw new EngineException("QIC 부족 (하이브 위성 비용: " + satellites.size() + ")");
            }
            p.setQic(p.getQic() - satellites.size()); // 하이브 위성 = QIC
        } else {
            // 일반 위성 = 파워 토큰 영구 제거 (브레인스톤 포함은 명시 선택 + FE 경고창 확정)
            removeTokens(p, satellites.size(),
                    Boolean.TRUE.equals(submit.payload().get("removeBrainstone")));
        }
        for (String key : satellites) {
            state.getHexes().get(key).getSatelliteOwners().add(submit.playerId());
        }
        if (existingGroup != null) {
            existingGroup.put("buildings", allBuildings);
            existingGroup.put("satellites", allSatellites);
        } else {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("buildings", new ArrayList<>(buildings));
            group.put("satellites", new ArrayList<>(satellites));
            p.getFederations().add(group);
        }
        p.setFederationsFormedCount(p.getFederationsFormedCount() + 1);

        Decision tileChoice = new Decision(state.newDecisionId(), "CHOOSE_FEDERATION_TILE",
                submit.playerId(), Map.of());
        state.getDecisionStack().add(tileChoice);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_FEDERATION_FORMED", submit,
                Map.of("power", power, "required", required,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))),
                List.of(Map.of("id", tileChoice.getId(), "type", "CHOOSE_FEDERATION_TILE", "target", submit.playerId()))));
    }

    /** 결성 보상 선택지 = 공급처 + 내가 입장한 함대의 확장 연방 타일 (함대 타일은 1장 — 선택 시 소진) */
    private List<EngineEvent> applyChooseFederationTile(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "CHOOSE_FEDERATION_TILE");
        String tileId = (String) submit.payload().get("tile");
        Map<String, Integer> supply = state.getBoard().getFederationSupply();
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);

        Integer count = supply.get(tileId);
        if (count != null && count >= 1) {
            supply.put(tileId, count - 1);
        } else {
            Map<String, String> fleetTiles = state.getBoard().getFleetFedTiles();
            String ship = fleetTiles.entrySet().stream()
                    .filter(e -> tileId.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (ship == null) {
                throw new EngineException("공급처에 없는 연방 타일입니다: " + tileId);
            }
            if (!p.getFleetProbes().contains(ship)) {
                throw new EngineException("해당 함대에 입장해야 선택할 수 있는 타일입니다: " + ship);
            }
            fleetTiles.remove(ship);
        }
        if (!p.getFederations().isEmpty()) {
            p.getFederations().get(p.getFederations().size() - 1).put("tile", tileId);
        }
        state.getDecisionStack().remove(top);
        grantFederationTile(state, submit.playerId(), tileId);

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("tile", tileId,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    private List<EngineEvent> applyPass(GameState state, Submit submit) {
        requireMainAction(state, submit);
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);

        applyPassVp(state, submit.playerId());
        if (state.getRound() < 6) {
            String newBooster = (String) submit.payload().get("booster");
            assignBooster(state, submit.playerId(), newBooster);
        }
        // 발타크: 패스 시 잔여 가이아포머 자동 QIC 변환
        if (hasAbility(faction(state, submit.playerId()), "FREE_GAIAFORMER_TO_QIC")) {
            int remaining = p.stockOf("GAIAFORMER");
            if (remaining > 0) {
                p.getStock().put("GAIAFORMER", 0);
                p.setBaltaksConvertedFormers(p.getBaltaksConvertedFormers() + remaining);
                p.setQic(p.getQic() + remaining);
            }
        }
        p.setPassed(true);
        state.getBoard().getPassOrder().add(submit.playerId());

        // 패스는 자유 행동 구간 없이 즉시 턴 전환 (game-spec §7.10)
        advanceTurn(state);
        return List.of(event("ACTION_PASSED", submit,
                Map.of("resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** 명시적 턴 종료 — 메인 액션 후 자유 행동 구간을 닫고 다음 플레이어로 (game-spec §7.10) */
    private List<EngineEvent> applyEndTurn(GameState state, Submit submit) {
        if (!"PLAYING".equals(state.getPhase()) || !state.getDecisionStack().isEmpty()
                || !submit.playerId().equals(state.getActivePlayer())) {
            throw new EngineException("자기 턴(대기 결정 없음)에만 턴을 종료할 수 있습니다");
        }
        if (!state.isTurnEndPending()) {
            throw new EngineException("메인 액션을 수행한 뒤에만 턴을 종료할 수 있습니다");
        }
        state.setTurnEndPending(false);
        advanceTurn(state);
        return List.of(event("TURN_ENDED", submit, Map.of(), List.of()));
    }

    /** 부스터 패스 점수 + 고급 타일 패스 점수 */
    private void applyPassVp(GameState state, String playerId) {
        PlayerState p = state.player(playerId);
        String passCategory = "PASS_" + state.getRound(); // 부스터 패스 점수는 라운드별 세분화 (J-12)
        if (p.getBooster() != null) {
            JsonNode booster = findTile(data.tiles().get("boosters"), p.getBooster());
            if (booster.has("passVp")) {
                int per = passVpCount(state, playerId, booster.get("passVp").get("per").asText());
                gainVp(p, per * booster.get("passVp").get("vp").asInt(), passCategory);
            }
        }
        // 고급 타일의 패스 발동 VP는 "고급 기술 타일" 항목으로 집계 (J-12)
        for (String tileId : p.getTechTiles()) {
            if (p.getCoveredTechTiles().contains(tileId) || !tileId.startsWith("ADV_")) {
                continue;
            }
            switch (findTile(data.tech().get("advancedTiles"), tileId).path("special").asText("")) {
                case "PASS_VP_3_PER_FEDERATION_TOKEN" -> gainVp(p, 3 * p.getFederationTokens().size(), "TECH_ADV");
                case "PASS_VP_3_PER_RESEARCH_LAB" -> gainVp(p, 3 * builtCount(state, playerId, "RESEARCH_LAB"), "TECH_ADV");
                case "PASS_VP_1_PER_PLANET_TYPE" -> gainVp(p, colonizedPlanetTypes(state, playerId).size(), "TECH_ADV");
                case "PASS_VP_2_PER_DEEP_SECTOR" -> gainVp(p, 2 * deepSectorsWithBuilding(state, playerId), "TECH_ADV");
                case "PASS_VP_2_PER_ASTEROID_AREA" -> gainVp(p, 2 * asteroidBuildings(state, playerId), "TECH_ADV");
                default -> { }
            }
        }
    }

    private int passVpCount(GameState state, String playerId, String per) {
        PlayerState p = state.player(playerId);
        return switch (per) {
            case "MINE" -> mineCountForScoring(state, playerId);
            case "TRADING_STATION" -> builtCount(state, playerId, "TRADING_STATION");
            case "RESEARCH_LAB" -> builtCount(state, playerId, "RESEARCH_LAB");
            case "PI_AND_ACADEMY" -> builtCount(state, playerId, "PLANETARY_INSTITUTE") + builtCount(state, playerId, "ACADEMY");
            case "GAIA_PLANET" -> gaiaPlanetCount(state, playerId);
            case "PLANET_TYPE" -> colonizedPlanetTypes(state, playerId).size();
            case "GAIAFORMER" -> p.stockOf("GAIAFORMER") + builtCount(state, playerId, "GAIAFORMER");
            case "DEEP_SECTOR_BUILDING" -> deepSectorBuildings(state, playerId);
            default -> 0;
        };
    }

    // ═══════════════ 프리 액션 (턴 미소모) / 특수 액션 (라운드 1회) ═══════════════

    /** 프리 액션 — 자기 턴에 자유롭게, 턴을 소모하지 않는 자원 변환 */
    private List<EngineEvent> applyFreeAction(GameState state, Submit submit) {
        if (!"PLAYING".equals(state.getPhase()) || !state.getDecisionStack().isEmpty()
                || !submit.playerId().equals(state.getActivePlayer())) {
            throw new EngineException("자기 턴(대기 결정 없음)에만 프리 액션을 쓸 수 있습니다");
        }
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);
        String conversion = (String) submit.payload().get("conversion");
        boolean useBrainstone = Boolean.TRUE.equals(submit.payload().get("useBrainstone"));

        switch (conversion == null ? "" : conversion) {
            // 표준 변환
            case "BURN" -> burnPower(p, faction);
            case "PW1_CREDIT" -> {
                spendPower(p, 1, useBrainstone, nevlasPiDouble(state, submit.playerId()));
                p.setCredits(p.getCredits() + 1);
            }
            case "PW3_ORE" -> {
                spendPower(p, 3, useBrainstone, nevlasPiDouble(state, submit.playerId()));
                p.setOre(p.getOre() + 1);
            }
            case "PW4_KNOWLEDGE" -> {
                spendPower(p, 4, useBrainstone, nevlasPiDouble(state, submit.playerId()));
                p.setKnowledge(p.getKnowledge() + 1);
            }
            case "PW4_QIC" -> {
                spendPower(p, 4, useBrainstone, nevlasPiDouble(state, submit.playerId()));
                addQic(state, submit.playerId(), 1);
            }
            case "KNOWLEDGE_CREDIT" -> {
                requireKnowledge(p, 1);
                p.setKnowledge(p.getKnowledge() - 1);
                p.setCredits(p.getCredits() + 1);
            }
            case "ORE_CREDIT" -> {
                requireResources(p, 0, 1, 0);
                p.setOre(p.getOre() - 1);
                p.setCredits(p.getCredits() + 1);
            }
            case "ORE_TOKEN" -> {
                requireResources(p, 0, 1, 0);
                p.setOre(p.getOre() - 1);
                p.setBowl1(p.getBowl1() + 1);
            }
            case "QIC_ORE" -> {
                requireResources(p, 0, 0, 1);
                p.setQic(p.getQic() - 1);
                p.setOre(p.getOre() + 1);
            }
            // 종족 프리 액션
            case "XENOS_ORE_POWER3" -> {
                requireFactionAbility(faction, "FREE_ORE_TO_POWER3");
                requireResources(p, 0, 1, 0);
                p.setOre(p.getOre() - 1);
                p.setBowl3(p.getBowl3() + 1);
            }
            case "BALTAKS_FORMER_QIC" -> {
                requireFactionAbility(faction, "FREE_GAIAFORMER_TO_QIC");
                decreaseStock(p, "GAIAFORMER");
                p.setBaltaksConvertedFormers(p.getBaltaksConvertedFormers() + 1);
                p.setQic(p.getQic() + 1);
            }
            case "NEVLAS_BOWL3_GAIA_K" -> {
                requireFactionAbility(faction, "FREE_POWER3_TO_GAIA_KNOWLEDGE_1");
                if (p.getBowl3() < 1) {
                    throw new EngineException("bowl3 토큰이 없습니다");
                }
                p.setBowl3(p.getBowl3() - 1);
                p.setGaiaPower(p.getGaiaPower() + 1);
                p.setKnowledge(p.getKnowledge() + 1);
            }
            case "HH_3C_ORE" -> hadschHallasConvert(state, submit.playerId(), 3, "ORE");
            case "HH_4C_KNOWLEDGE" -> hadschHallasConvert(state, submit.playerId(), 4, "KNOWLEDGE");
            case "HH_4C_QIC" -> hadschHallasConvert(state, submit.playerId(), 4, "QIC");
            case "NEVLAS_2T_ORE_CREDIT" -> nevlasTokenConvert(state, submit.playerId(), 2, 1, 1);
            case "NEVLAS_3T_ORE2" -> nevlasTokenConvert(state, submit.playerId(), 3, 2, 0);
            default -> throw new EngineException("알 수 없는 변환: " + conversion);
        }
        // 턴 미소모 — turnEndPending 설정하지 않음
        return List.of(event("FREE_ACTION_CONVERTED", submit,
                Map.of("conversion", conversion,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** 소각: bowl2에서 2개 제거 → 1개 bowl3. 아이타는 제거분 1개가 가이아 구역으로. 브레인스톤은 소각 제거 대상 아님(기본) */
    private void burnPower(PlayerState p, JsonNode faction) {
        if (p.getBowl2() < 2) {
            throw new EngineException("bowl2 토큰 부족 (소각에 2개 필요)");
        }
        p.setBowl2(p.getBowl2() - 2);
        p.setBowl3(p.getBowl3() + 1);
        if (hasAbility(faction, "BURN_TO_GAIA")) {
            p.setGaiaPower(p.getGaiaPower() + 1); // 아이타: 소각 제거분이 가이아 구역으로
        }
    }

    /** 네블라 PI 전용 변환: bowl3 토큰 n개 사용(2파워 가치) → 자원 */
    private void nevlasTokenConvert(GameState state, String playerId, int tokens, int ore, int credits) {
        PlayerState p = state.player(playerId);
        if (!nevlasPiDouble(state, playerId)) {
            throw new EngineException("네블라 행성 의회 건설 후 사용 가능합니다");
        }
        if (p.getBowl3() < tokens) {
            throw new EngineException("bowl3 토큰 부족 (필요: " + tokens + ")");
        }
        p.setBowl3(p.getBowl3() - tokens);
        p.setBowl1(p.getBowl1() + tokens);
        p.setOre(p.getOre() + ore);
        p.setCredits(p.getCredits() + credits);
    }

    private boolean nevlasPiDouble(GameState state, String playerId) {
        PlayerState p = state.player(playerId);
        return hasAbility(data.faction(p.getFaction()), "PI_BOWL3_DOUBLE_VALUE")
                && builtCount(state, playerId, "PLANETARY_INSTITUTE") > 0;
    }

    private void hadschHallasConvert(GameState state, String playerId, int cost, String target) {
        PlayerState p = state.player(playerId);
        requireFactionAbility(faction(state, playerId), "PI_FREE_CREDIT_CONVERT");
        if (builtCount(state, playerId, "PLANETARY_INSTITUTE") == 0) {
            throw new EngineException("행성 의회 건설 후 사용 가능합니다");
        }
        requireResources(p, cost, 0, 0);
        p.setCredits(p.getCredits() - cost);
        switch (target) {
            case "ORE" -> p.setOre(p.getOre() + 1);
            case "KNOWLEDGE" -> p.setKnowledge(p.getKnowledge() + 1);
            case "QIC" -> p.setQic(p.getQic() + 1);
            default -> { }
        }
    }

    /** 특수 액션 (오렌지) — 종족/부스터/기술 타일, 각 라운드 1회, 턴 소모 */
    private List<EngineEvent> applySpecialAction(GameState state, Submit submit) {
        requireMainAction(state, submit);
        String source = (String) submit.payload().get("source");
        String id = (String) submit.payload().get("id");
        String key = source + ":" + id;
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        if (p.getUsedSpecialActions().contains(key)) {
            throw new EngineException("이번 라운드에 이미 사용한 특수 액션입니다: " + key);
        }
        switch (source == null ? "" : source) {
            case "FACTION" -> requireFactionAbility(faction, id);
            case "BOOSTER" -> {
                if (!id.equals(p.getBooster())) {
                    throw new EngineException("보유 중인 부스터가 아닙니다: " + id);
                }
            }
            case "TECH_TILE" -> {
                if (!p.getTechTiles().contains(id) || p.getCoveredTechTiles().contains(id)) {
                    throw new EngineException("사용할 수 없는 기술 타일입니다: " + id);
                }
            }
            case "BUILDING" -> {
                if (!"ACADEMY_QIC".equals(id) || qicAcademyCount(state, submit.playerId()) == 0) {
                    throw new EngineException("사용할 수 없는 건물 액션입니다: " + id);
                }
            }
            default -> throw new EngineException("특수 액션 출처를 지정해야 합니다 (FACTION/BOOSTER/TECH_TILE/BUILDING)");
        }
        if (id.startsWith("PI_ACTION") && builtCount(state, submit.playerId(), "PLANETARY_INSTITUTE") == 0) {
            throw new EngineException("행성 의회 건설 후 사용 가능합니다");
        }
        Map<String, Object> before = resourceSnapshot(p);
        List<Map<String, Object>> pushed = new ArrayList<>();

        switch (id) {
            // 기술 타일 액션
            case "BASIC_TILE_1" -> chargePower(p, 4);
            case "ADV_TILE_7" -> p.setOre(p.getOre() + 3);
            case "ADV_TILE_8" -> p.setKnowledge(p.getKnowledge() + 3);
            case "ADV_TILE_9" -> {
                addQic(state, submit.playerId(), 1);
                p.setCredits(p.getCredits() + 5);
            }
            // 부스터 액션
            case "BOOSTER_12" -> instantGaiaform(state, submit);
            case "BOOSTER_13" -> pushed.add(pushFreeMine(state, submit.playerId(), 0, false, 3, null));
            case "BOOSTER_14" -> pushed.add(pushFreeMine(state, submit.playerId(), 1, false, 0, null));
            // 종족 특수 액션
            case "ACTION_JUMP_RANGE_2" -> pushed.add(pushFreeMine(state, submit.playerId(), 0, false, 2, null));
            case "ACTION_TERRAFORM_2_PLACE_MINE" -> pushed.add(pushFreeMine(state, submit.playerId(), 2, false, 0, null));
            case "ACTION_ADVANCE_LOWEST_TRACK" -> bescodsAdvanceLowest(state, submit);
            case "PI_ACTION_SWAP_MINE_PI" -> ambasSwap(state, submit);
            case "PI_ACTION_FREE_LAB_TO_TS_UPGRADE" -> pushed.addAll(firaksFreeUpgrade(state, submit));
            case "PI_ACTION_SPACE_STATION" -> ivitsPlaceStation(state, submit);
            case "PI_ACTION_ATTACH_RING" -> moweidsAttachRing(state, submit);
            case "PI_PERSONAL_ACTION_TILES" -> pushed.addAll(tinkeroidsUseAction(state, submit));
            // 건물 액션: QIC 아카데미 (라운드 1회) — 발타크는 QIC 대신 크레딧 4 (I-1·I-12)
            case "ACADEMY_QIC" -> {
                if (hasAbility(faction, "QIC_ACADEMY_CREDITS_4")) {
                    p.setCredits(p.getCredits() + 4);
                } else {
                    addQic(state, submit.playerId(), 1);
                }
            }
            default -> throw new EngineException("알 수 없는 특수 액션: " + id);
        }
        p.getUsedSpecialActions().add(key);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_SPECIAL_ACTION", submit,
                Map.of("action", key,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), pushed));
    }

    /** 매드 안드로이드: 최저 레벨 트랙 하나 +1 (지식 무료). 최저가 여럿이면 그중 선택 */
    private void bescodsAdvanceLowest(GameState state, Submit submit) {
        String track = (String) submit.payload().get("track");
        if (track == null || !TRACK_NAMES.contains(track)) {
            throw new EngineException("전진할 트랙을 지정해야 합니다");
        }
        PlayerState p = state.player(submit.playerId());
        int min = TRACK_NAMES.stream().mapToInt(p::track).min().orElse(0);
        if (p.track(track) != min) {
            throw new EngineException("최저 레벨 트랙만 전진할 수 있습니다 (" + track + "=" + p.track(track) + ", 최저=" + min + ")");
        }
        paidTrackAdvance(state, submit.playerId(), track);
    }

    /** 엠바스 PI: 광산 ↔ 의회 위치 교환 (리치 없음) */
    private void ambasSwap(GameState state, Submit submit) {
        HexState mineHex = hexAt(state, submit.payload(), "mineQ", "mineR");
        HexState piHex = hexAt(state, submit.payload(), "piQ", "piR");
        String playerId = submit.playerId();
        if (!playerId.equals(mineHex.getBuildingOwner()) || !"MINE".equals(mineHex.getBuildingType())
                || !playerId.equals(piHex.getBuildingOwner()) || !"PLANETARY_INSTITUTE".equals(piHex.getBuildingType())) {
            throw new EngineException("본인의 광산과 행성 의회를 지정해야 합니다");
        }
        mineHex.setBuildingType("PLANETARY_INSTITUTE");
        piHex.setBuildingType("MINE");
    }

    /** 파이락 PI: 연구소 → 교역소 무료 업그레이드 + 지식 트랙 1칸 (✅명명 확정 — 일반 업그레이드 취급, 커밋은 upgradeCore 공용) */
    private List<Map<String, Object>> firaksFreeUpgrade(GameState state, Submit submit) {
        HexState hex = requireHex(state, submit);
        if (!submit.playerId().equals(hex.getBuildingOwner()) || !"RESEARCH_LAB".equals(hex.getBuildingType())) {
            throw new EngineException("본인 연구소를 지정해야 합니다");
        }
        List<Map<String, Object>> pushed = upgradeCore(state, submit, hex, coordOf(submit), "TRADING_STATION");
        advanceTrackIfPossible(state, submit.playerId(), "SCIENCE");
        return pushed;
    }

    /** 하이브 PI: 빈 우주에 우주정거장 배치 (리치 없음 ✅확정, 파워값 1 — 연방 구성 요소) */
    private void ivitsPlaceStation(GameState state, Submit submit) {
        HexState hex = requireHex(state, submit);
        PlayerState p = state.player(submit.playerId());
        if (!"EMPTY".equals(hex.getPlanet()) || hex.hasBuilding() || hex.getShip() != null) {
            throw new EngineException("우주정거장은 빈 우주 헥스에만 배치할 수 있습니다"); // 위성 있는 헥스는 허용
        }
        int qicForRange = intOf(submit.payload(), "qicForRange");
        checkRange(state, submit.playerId(), coordOf(submit), p, qicForRange, 0);
        requireResources(p, 0, 0, qicForRange);
        p.setQic(p.getQic() - qicForRange);
        hex.setBuildingOwner(submit.playerId());
        hex.setBuildingType("SPACE_STATION");
        autoIncorporateIntoFederation(state, submit.playerId(), coordOf(submit)); // §2: 인접 연방 자동 편입
    }

    /** 모웨이드 PI: 본인 건물에 링 부착 → 파워값 +2 (건물당 1회, 가이아포머 불가) */
    private void moweidsAttachRing(GameState state, Submit submit) {
        HexState hex = requireHex(state, submit);
        if (!submit.playerId().equals(hex.getBuildingOwner()) || "GAIAFORMER".equals(hex.getBuildingType())) {
            throw new EngineException("본인 건물(가이아포머 제외)에만 링을 부착할 수 있습니다");
        }
        if (hex.isRing()) {
            throw new EngineException("이미 링이 부착된 건물입니다");
        }
        hex.setRing(true);
    }

    // ═══ 팅커로이드 개인 액션 타일 ═══

    private static final List<String> TINK_POOL_EARLY = List.of("TINK_TERRAFORM_1", "TINK_POWER_4", "TINK_QIC_1");
    private static final List<String> TINK_POOL_LATE = List.of("TINK_TERRAFORM_3", "TINK_KNOWLEDGE_3", "TINK_QIC_2");

    /**
     * 종족 능력 페이즈 (수입·가이아 페이즈 후, ✅확정 2026-07-22):
     * 아이타 가이아 테크·팅커로이드 액션 타일 선택 — 이번 라운드 턴 순서(패스 순서)대로 선 플레이어부터 해소.
     * 스택은 LIFO이므로 턴 역순으로 push한다.
     */
    private void pushAbilityPhase(GameState state) {
        List<String> order = state.getTurnOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            String playerId = order.get(i);
            PlayerState p = state.player(playerId);
            JsonNode faction = data.faction(p.getFaction());
            if (hasAbility(faction, "PI_GAIA_4_TO_TECH_TILE")
                    && builtCount(state, playerId, "PLANETARY_INSTITUTE") > 0
                    && p.getGaiaPower() >= 4) {
                pushDecision(state, "ITARS_GAIA_TECH", playerId, Map.of("tokens", p.getGaiaPower()));
            }
            if (hasAbility(faction, "PI_PERSONAL_ACTION_TILES")
                    && builtCount(state, playerId, "PLANETARY_INSTITUTE") > 0) {
                List<String> options = tinkeroidsOptions(state, p);
                if (!options.isEmpty()) {
                    pushDecision(state, "TINKEROIDS_ACTION_PICK", playerId, Map.of("options", options));
                }
            }
        }
    }

    private List<String> tinkeroidsOptions(GameState state, PlayerState p) {
        List<String> pool = state.getRound() <= 3 ? TINK_POOL_EARLY : TINK_POOL_LATE;
        return pool.stream().filter(tile -> !p.getTinkeroidsUsedTiles().contains(tile)).toList();
    }

    private List<EngineEvent> applyTinkeroidsPick(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "TINKEROIDS_ACTION_PICK");
        String tile = (String) submit.payload().get("tile");
        PlayerState p = state.player(submit.playerId());
        if (tile == null || !tinkeroidsOptions(state, p).contains(tile)) {
            throw new EngineException("선택할 수 없는 액션 타일입니다: " + tile);
        }
        p.getTinkeroidsUsedTiles().add(tile);
        p.setTinkeroidsCurrentAction(tile);
        state.getDecisionStack().remove(top);
        return List.of(event("DECISION_RESOLVED", submit, Map.of("tile", tile), List.of()));
    }

    /** 선택된 개인 액션 사용 (메인 액션, 라운드당 1회) */
    private List<Map<String, Object>> tinkeroidsUseAction(GameState state, Submit submit) {
        PlayerState p = state.player(submit.playerId());
        String tile = p.getTinkeroidsCurrentAction();
        if (tile == null) {
            throw new EngineException("사용 가능한 개인 액션이 없습니다 (라운드 시작 시 선택 필요)");
        }
        p.setTinkeroidsCurrentAction(null);
        List<Map<String, Object>> pushed = new ArrayList<>();
        switch (tile) {
            case "TINK_TERRAFORM_1" -> pushed.add(pushFreeMine(state, submit.playerId(), 1, false, 0, null));
            case "TINK_TERRAFORM_3" -> pushed.add(pushFreeMine(state, submit.playerId(), 3, false, 0, null));
            case "TINK_POWER_4" -> chargePower(p, 4);
            case "TINK_QIC_1" -> addQic(state, submit.playerId(), 1);
            case "TINK_QIC_2" -> addQic(state, submit.playerId(), 2);
            case "TINK_KNOWLEDGE_3" -> p.setKnowledge(p.getKnowledge() + 3);
            default -> throw new EngineException("알 수 없는 개인 액션: " + tile);
        }
        return pushed;
    }

    private HexState hexAt(GameState state, Map<String, Object> payload, String qKey, String rKey) {
        HexState hex = state.getHexes().get(new HexCoord(intOf(payload, qKey), intOf(payload, rKey)).key());
        if (hex == null) {
            throw new EngineException("존재하지 않는 헥스입니다");
        }
        return hex;
    }

    private void requireFactionAbility(JsonNode faction, String ability) {
        if (!hasAbility(faction, ability)) {
            throw new EngineException("이 종족의 능력이 아닙니다: " + ability);
        }
    }

    private void requireKnowledge(PlayerState p, int amount) {
        if (p.getKnowledge() < amount) {
            throw new EngineException("지식 부족 (필요: " + amount + ")");
        }
    }

    // ═══════════════ Lost Fleet: 함대 입장 / 함대 액션 / 인공물 ═══════════════

    private static final Set<String> FLEET_SHIPS = Set.of("TF_MARS", "REBELLION", "ECLIPSE", "TWILIGHT");

    private List<EngineEvent> applyFleetEnter(GameState state, Submit submit) {
        requireMainAction(state, submit);
        String ship = (String) submit.payload().get("ship");
        if (ship == null || !FLEET_SHIPS.contains(ship)) {
            throw new EngineException("함대를 지정해야 합니다");
        }
        PlayerState p = state.player(submit.playerId());
        JsonNode faction = faction(state, submit.playerId());
        JsonNode fleet = data.actions().get("fleet");
        if (p.getFleetProbes().contains(ship)) {
            throw new EngineException("이미 입장한 함대입니다: " + ship);
        }
        if (p.getFleetProbes().size() >= fleet.get("maxProbesPerPlayer").asInt()) {
            throw new EngineException("함대 입장은 플레이어당 최대 3회입니다");
        }
        int vpCost = faction.path("fleetEntryVp").asInt(fleet.get("entryVp").asInt());
        if (p.getVp() < vpCost) {
            throw new EngineException("VP 부족 (함대 입장 비용: " + vpCost + ")");
        }
        // 항해 거리 규칙 — 광산 건설과 동일: 초과분은 QIC로 지불 (game-spec 7.7-2 확정)
        HexCoord shipCoord = state.getHexes().entrySet().stream()
                .filter(e -> ship.equals(e.getValue().getShip()))
                .map(e -> HexCoord.parse(e.getKey()))
                .findFirst()
                .orElseThrow(() -> new EngineException("맵에 없는 함대입니다: " + ship));
        int qicForRange = intOf(submit.payload(), "qicForRange");
        checkRange(state, submit.playerId(), shipCoord, p, qicForRange, 0);
        requireResources(p, 0, 0, qicForRange);
        Map<String, Object> before = resourceSnapshot(p);
        p.setQic(p.getQic() - qicForRange);

        // 종족 추가 비용: 네블라·아이타 토큰 1 영구 소각, 타클론 브레인스톤 가이아 이동
        if (hasAbility(faction, "FLEET_ENTRY_BURN_TOKEN")) {
            removeTokens(p, 1, Boolean.TRUE.equals(submit.payload().get("removeBrainstone")));
        }
        if (hasAbility(faction, "BRAINSTONE")) {
            if (p.getBrainstone() == null || "GAIA".equals(p.getBrainstone())) {
                throw new EngineException("브레인스톤을 가이아 구역으로 이동할 수 없어 입장할 수 없습니다");
            }
            p.setBrainstone("GAIA");
        }
        gainVp(p, -vpCost, "FLEET");

        // 입장 순서 보너스 (같은 함대 기준): 2·3번째 파순 2, 4번째 파순 3
        int alreadyEntered = (int) state.getPlayers().values().stream()
                .filter(other -> other != p && other.getFleetProbes().contains(ship))
                .count();
        JsonNode bonus = fleet.get("entryOrderPowerCharge");
        if (alreadyEntered < bonus.size()) {
            chargePower(p, bonus.get(alreadyEntered).asInt());
        }
        p.getFleetProbes().add(ship);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_FLEET_ENTERED", submit,
                Map.of("ship", ship,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** 함대 액션 — 해당 함대 입장자만, 파워 액션과 동일한 라운드당 전체 1회 풀 공유 (v1 방식) */
    private List<EngineEvent> applyFleetAction(GameState state, Submit submit) {
        requireMainAction(state, submit);
        String actionId = (String) submit.payload().get("actionId");
        JsonNode action = findTile(data.actions().get("fleetActions"), actionId);
        PlayerState p = state.player(submit.playerId());
        if (!p.getFleetProbes().contains(action.get("ship").asText())) {
            throw new EngineException("해당 함대에 입장해야 사용할 수 있습니다: " + action.get("ship").asText());
        }
        if (state.getBoard().getPowerActionsUsedThisRound().contains(actionId)) {
            throw new EngineException("이번 라운드에 이미 사용된 액션입니다: " + actionId);
        }
        Map<String, Object> before = resourceSnapshot(p);
        payFleetCost(p, action.get("cost"),
                Boolean.TRUE.equals(submit.payload().get("useBrainstone")),
                Boolean.TRUE.equals(submit.payload().get("removeBrainstone")),
                nevlasPiDouble(state, submit.playerId()));
        if (action.has("gain")) {
            gainResources(state, submit.playerId(), action.get("gain"), "FLEET");
        }

        List<Map<String, Object>> pushed = new ArrayList<>();
        switch (action.path("special").asText("")) {
            case "VP_2_PLUS_1_PER_TECH_TILE" -> gainVp(p, 2 + p.getTechTiles().size(), "FLEET");
            case "VP_2_PLUS_1_PER_PLANET_TYPE" -> gainVp(p, 2 + planetTypesWithArtifacts(state, submit.playerId()), "FLEET");
            case "INSTANT_GAIAFORM" -> instantGaiaform(state, submit);
            case "BUILD_MINE_TERRAFORM_1_FREE" -> pushed.add(pushFreeMine(state, submit.playerId(), 1, false, 0, null));
            case "ADVANCE_TRACK_1" -> paidTrackAdvance(state, submit.playerId(), (String) submit.payload().get("track"));
            case "BUILD_MINE_ON_ASTEROIDS" -> pushed.add(pushFreeMine(state, submit.playerId(), 0, true, 0, "ASTEROIDS"));
            case "GAIN_TECH_TILE" -> pushed.add(pushDecision(state, "CHOOSE_TECH_TILE", submit.playerId(), Map.of()));
            case "UPGRADE_MINE_TO_TS" -> pushed.addAll(fleetUpgrade(state, submit, "MINE", "TRADING_STATION"));
            case "UPGRADE_TS_TO_LAB_WITH_TECH_TILE" -> pushed.addAll(fleetUpgrade(state, submit, "TRADING_STATION", "RESEARCH_LAB"));
            case "BUILD_MINE_RANGE_PLUS_3" -> pushed.add(pushFreeMine(state, submit.playerId(), 0, false, 3, null));
            case "REUSE_FEDERATION_TOKEN" -> {
                if (p.getFederationTokens().isEmpty()) {
                    throw new EngineException("보유한 연방 토큰이 없어 사용할 수 없는 액션입니다");
                }
                pushed.add(pushDecision(state, "CHOOSE_FED_TOKEN_REUSE", submit.playerId(), Map.of()));
            }
            case "ACQUIRE_ARTIFACT" -> pushed.add(pushDecision(state, "CHOOSE_ARTIFACT", submit.playerId(), Map.of()));
            default -> { }
        }
        // QIC 액션 보너스 (ADV_TILE_21: QIC 액션당 4VP)
        if (action.path("qicAction").asBoolean(false)
                && p.getTechTiles().contains("ADV_TILE_21") && !p.getCoveredTechTiles().contains("ADV_TILE_21")) {
            gainVp(p, 4, "QIC_ACTION"); // QIC 액션마다 +4VP (고급㉑) — 점수 창 별도 컬럼 (J-12)
        }
        state.getBoard().getPowerActionsUsedThisRound().add(actionId);

        state.setTurnEndPending(true);
        return List.of(event("ACTION_FLEET_ACTION", submit,
                Map.of("actionId", actionId,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), pushed));
    }

    private void payFleetCost(PlayerState p, JsonNode cost, boolean useBrainstone,
                              boolean removeBrainstone, boolean nevlasDouble) {
        int credits = cost.path("credits").asInt(0);
        int ore = cost.path("ore").asInt(0);
        int qic = cost.path("qic").asInt(0);
        int knowledge = cost.path("knowledge").asInt(0);
        requireResources(p, credits, ore, qic);
        requireKnowledge(p, knowledge);
        p.setCredits(p.getCredits() - credits);
        p.setOre(p.getOre() - ore);
        p.setQic(p.getQic() - qic);
        p.setKnowledge(p.getKnowledge() - knowledge);
        if (cost.has("power")) {
            spendPower(p, cost.get("power").asInt(), useBrainstone, nevlasDouble);
        }
        if (cost.has("burnTokens")) {
            removeTokens(p, cost.get("burnTokens").asInt(), removeBrainstone);
        }
    }

    /** TF_MARS_GAIAFORM: 차원 변형 즉시 가이아 변환 + 포머 배치 */
    private void instantGaiaform(GameState state, Submit submit) {
        HexState hex = requireHex(state, submit);
        PlayerState p = state.player(submit.playerId());
        if (!"TRANSDIM".equals(hex.getPlanet()) || hex.hasBuilding()) {
            throw new EngineException("차원 변형 행성에만 즉시 포밍이 가능합니다");
        }
        if (p.stockOf("GAIAFORMER") < 1) {
            throw new EngineException("사용 가능한 가이아포머가 없습니다");
        }
        int qicForRange = intOf(submit.payload(), "qicForRange");
        checkRange(state, submit.playerId(), coordOf(submit), p, qicForRange, 0);
        requireResources(p, 0, 0, qicForRange);
        p.setQic(p.getQic() - qicForRange);
        decreaseStock(p, "GAIAFORMER");
        hex.setBuildingOwner(submit.playerId());
        hex.setBuildingType("GAIAFORMER");
        hex.setPlanet("GAIA");
    }

    /** 함대 업그레이드 (비용은 액션 비용에 포함 — 건설 자원 없음) */
    /** 함대 업그레이드 (리벨리온 광산→교역소, 트와일라잇 교역소→연구소): 비용은 함대 액션 비용으로 대체 — 커밋은 upgradeCore 공용 */
    private List<Map<String, Object>> fleetUpgrade(GameState state, Submit submit, String from, String to) {
        HexState hex = requireHex(state, submit);
        if (!submit.playerId().equals(hex.getBuildingOwner()) || !from.equals(hex.getBuildingType())) {
            throw new EngineException("대상이 본인 " + from + "이(가) 아닙니다");
        }
        return upgradeCore(state, submit, hex, coordOf(submit), to);
    }

    /** 함대 액션의 유료 트랙 전진 (검증 실패 시 예외 — 무료 전진과 달리 스킵하지 않음) */
    private void paidTrackAdvance(GameState state, String playerId, String track) {
        if (track == null || !TRACK_NAMES.contains(track)) {
            throw new EngineException("전진할 트랙을 지정해야 합니다");
        }
        if (navigationLocked(state, playerId, track)) {
            throw new EngineException("발타크는 의회 건설 전까지 항해 트랙 전진 불가");
        }
        PlayerState p = state.player(playerId);
        int level = p.track(track);
        if (level >= 5) {
            throw new EngineException("트랙 최대 레벨입니다");
        }
        if (level + 1 == 5) {
            if (state.getBoard().getTrackLevel5Occupied().containsKey(track)) {
                throw new EngineException("5단계가 이미 점유된 트랙입니다");
            }
            flipUsableFederationToken(p);
            state.getBoard().getTrackLevel5Occupied().put(track, playerId);
        }
        applyTrackAdvance(state, playerId, track, level + 1);
    }

    /** TWILIGHT_FED / ARTIFACT_13: 보유 연방 토큰 보상 재수령 */
    private List<EngineEvent> applyFedTokenReuse(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "CHOOSE_FED_TOKEN_REUSE");
        String token = (String) submit.payload().get("token");
        PlayerState p = state.player(submit.playerId());
        if (token == null || !p.getFederationTokens().contains(token)) {
            throw new EngineException("보유하지 않은 연방 토큰입니다: " + token);
        }
        Map<String, Object> before = resourceSnapshot(p);
        state.getDecisionStack().remove(top);
        applyFederationTileEffects(state, submit.playerId(), findTile(data.tiles().get("federationTiles"), token));

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("token", token,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    private List<EngineEvent> applyChooseArtifact(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "CHOOSE_ARTIFACT");
        String artifactId = (String) submit.payload().get("artifact");
        Map<String, String> offers = state.getBoard().getArtifactOffers();
        if (artifactId == null || !offers.containsKey(artifactId)) {
            throw new EngineException("오퍼에 없는 인공물입니다: " + artifactId);
        }
        if (offers.get(artifactId) != null) {
            throw new EngineException("이미 선점된 인공물입니다: " + artifactId);
        }
        PlayerState p = state.player(submit.playerId());
        JsonNode artifact = findTile(data.actions().get("artifacts"), artifactId);
        // ⑬ 연방 토큰 재수령: 보유 토큰이 없으면 획득 불가 (✅확정 2026-07-23)
        if ("FEDERATION_TOKEN_DOUBLE_USE".equals(artifact.path("special").asText(""))
                && p.getFederationTokens().isEmpty()) {
            throw new EngineException("보유한 연방 토큰이 없어 획득할 수 없는 인공물입니다");
        }
        Map<String, Object> before = resourceSnapshot(p);

        offers.put(artifactId, submit.playerId());
        p.getArtifacts().add(artifactId);
        state.getDecisionStack().remove(top);
        if (artifact.has("gain")) {
            gainResources(state, submit.playerId(), artifact.get("gain"), "ARTIFACT");
        }
        switch (artifact.path("special").asText("")) {
            case "VP_3_PER_DEEP_SECTOR_WITH_BUILDING" -> gainVp(p, 3 * deepSectorsWithBuilding(state, submit.playerId()), "ARTIFACT");
            case "VP_7_ASTEROIDS_AS_PLANET_TYPE_BUILDING_PLUS_1",
                 "VP_7_TRANSCENDENT_AS_PLANET_TYPE_BUILDING_PLUS_1" -> {
                gainVp(p, 7, "ARTIFACT");
                // 획득 = 가상 광산 건설 행동 취급 (H-3): 라운드 점수 + 건설 VP 타일 발동, 수입·재고·리치는 무관
                roundScore(state, p, "MINE_PLACED", 1);
                techMineBuildVp(p, false);
            }
            case "VP_3_PER_SCIENCE_LEVEL" -> gainVp(p, 3 * p.track("SCIENCE"), "ARTIFACT");
            case "VP_3_PER_GAIA_LEVEL" -> gainVp(p, 3 * p.track("GAIA_FORMING"), "ARTIFACT");
            case "VP_3_PER_TRACK_LEVEL3_PLUS" -> {
                int count = (int) p.getTracks().values().stream().filter(level -> level >= 3).count();
                gainVp(p, 3 * count, "ARTIFACT");
            }
            case "VP_3_PLUS_1_PER_PLANET_TYPE" -> gainVp(p, 3 + planetTypesWithArtifacts(state, submit.playerId()), "ARTIFACT");
            case "FEDERATION_TOKEN_DOUBLE_USE" -> pushDecision(state, "CHOOSE_FED_TOKEN_REUSE", submit.playerId(), Map.of());
            default -> { }
        }

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("artifact", artifactId,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** QIC형 아카데미 보유 수 — 건물 특수 액션(ACADEMY_QIC) 사용 조건 */
    private int qicAcademyCount(GameState state, String playerId) {
        return (int) state.getHexes().values().stream()
                .filter(h -> playerId.equals(h.getBuildingOwner()) && "ACADEMY".equals(h.getBuildingType())
                        && "QIC".equals(h.getAcademyType()))
                .count();
    }

    /** 점수용 광산 수 — 인공물 ⑦⑧ 가상 광산·검은행성 광산 포함 (수입 광산 수에는 미포함 — 3번째 광산 슬롯을 밀지 않도록) */
    private int mineCountForScoring(GameState state, String playerId) {
        return builtCount(state, playerId, "MINE")
                + builtCount(state, playerId, "BLACK_PLANET_MINE")
                + (int) state.player(playerId).getArtifacts().stream()
                        .filter(a -> a.equals("ARTIFACT_7") || a.equals("ARTIFACT_8")).count();
    }

    /** 행성 종류 수 — ARTIFACT_7/8의 가상 종류 포함 */
    private int planetTypesWithArtifacts(GameState state, String playerId) {
        PlayerState p = state.player(playerId);
        int extra = (int) p.getArtifacts().stream()
                .filter(a -> a.equals("ARTIFACT_7") || a.equals("ARTIFACT_8"))
                .count();
        return colonizedPlanetTypes(state, playerId).size() + extra;
    }

    private Map<String, Object> pushDecision(GameState state, String type, String target, Map<String, Object> context) {
        Decision d = new Decision(state.newDecisionId(), type, target, context);
        state.getDecisionStack().add(d);
        return Map.of("id", d.getId(), "type", type, "target", target);
    }

    // ═══════════════ 무료 광산 / 검은행성 ═══════════════

    private List<EngineEvent> applyFreeMine(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "PLACE_MINE");
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);
        int freeShovels = (int) top.getContext().getOrDefault("freeShovels", 0);
        boolean freeBuild = Boolean.TRUE.equals(top.getContext().get("freeBuild"));
        int rangeBonus = (int) top.getContext().getOrDefault("rangeBonus", 0);
        String planetOnly = (String) top.getContext().get("planetOnly");

        state.getDecisionStack().remove(top);
        List<Map<String, Object>> pushed = buildMineCore(state, submit,
                intOf(submit.payload(), "qicForRange"), freeShovels, freeBuild, rangeBonus, planetOnly);

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), pushed));
    }

    private List<EngineEvent> applyPlaceBlackPlanet(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "PLACE_BLACK_PLANET");
        HexState hex = requireHex(state, submit);
        HexCoord target = coordOf(submit);
        PlayerState p = state.player(submit.playerId());

        if (!"EMPTY".equals(hex.getPlanet()) || hex.hasBuilding()) {
            throw new EngineException("검은행성은 빈 우주 헥스에만 배치할 수 있습니다");
        }
        int qicForRange = intOf(submit.payload(), "qicForRange");
        checkRange(state, submit.playerId(), target, p, qicForRange, 0);
        requireResources(p, 0, 0, qicForRange);
        p.setQic(p.getQic() - qicForRange);

        boolean newSector = !hasPresenceInSector(state, submit.playerId(), hex.getSectorId());
        hex.setPlanet("BLACK_PLANET");
        hex.setBuildingOwner(submit.playerId());
        hex.setBuildingType("BLACK_PLANET_MINE"); // 광산 취급 (재고 미소모, 수입 무관 — E-3)
        roundScore(state, p, "MINE_PLACED", 1);
        techMineBuildVp(p, false); // 광산 건설 행동 취급 — 고급⑯ 발동 (인공물 ⑦⑧과 동일 규칙)
        newColonizationTriggers(state, submit.playerId(), newSector, true); // 검은행성 = 항상 새 행성 종류
        autoIncorporateIntoFederation(state, submit.playerId(), target);

        state.getDecisionStack().remove(top);
        List<Map<String, Object>> pushed = pushLeechDecisions(state, submit.playerId(), target);

        return List.of(event("DECISION_RESOLVED", submit, Map.of("planet", "BLACK_PLANET"), pushed));
    }

    // ═══════════════ 파워 리치 ═══════════════

    /**
     * 새 건물 기준 거리 2 이내 상대에게 리치 오퍼 생성 (edge-cases.md §4).
     * 시계방향(턴 순서) 순으로 응답 — 스택 LIFO이므로 역순 push.
     * 1파워(무비용)는 자동 수락 (아이타·타클론 PI는 수동).
     * 6라운드 패스자는 1파워 자동 수락만 지급 — 수동 예외도 무시, 2파워 이상 오퍼는 미발생.
     */
    private List<Map<String, Object>> pushLeechDecisions(GameState state, String builderId, HexCoord built) {
        int radius = data.constants().get("leech").get("radius").asInt();
        List<Decision> toPush = new ArrayList<>();

        for (String opponentId : clockwiseFrom(state, builderId)) {
            int base = 0;
            for (Map.Entry<String, HexState> e : state.getHexes().entrySet()) {
                HexState h = e.getValue();
                if (HexCoord.parse(e.getKey()).distance(built) > radius) {
                    continue;
                }
                if (opponentId.equals(h.getBuildingOwner())) {
                    base = Math.max(base, buildingPowerValue(state, opponentId, h)); // 연방과 동일 함수
                }
                if (opponentId.equals(h.getParasiteOwner())) {
                    base = Math.max(base, 1); // 란티다 기생 광산은 항상 파워 1
                }
            }
            if (base == 0) {
                continue;
            }
            PlayerState opponent = state.player(opponentId);
            int brainstoneCharge = "BOWL1".equals(opponent.getBrainstone()) ? 2
                    : "BOWL2".equals(opponent.getBrainstone()) ? 1 : 0;
            int chargeable = opponent.getBowl1() * 2 + opponent.getBowl2() + brainstoneCharge;
            int effective = Math.min(base, chargeable);
            if (effective <= 0) {
                continue;
            }
            int amount = Math.min(effective, Math.max(opponent.getVp(), 0) + 1);
            boolean passedFinalRound = state.getRound() >= 6 && opponent.isPassed();
            if (passedFinalRound) {
                if (amount == 1) {
                    chargePower(opponent, 1);
                }
                continue;
            }
            JsonNode opponentFaction = data.faction(opponent.getFaction());
            boolean taklonsPi = hasAbility(opponentFaction, "PI_LEECH_EXTRA_TOKEN")
                    && builtCount(state, opponentId, "PLANETARY_INSTITUTE") > 0;
            boolean manualOne = hasAbility(opponentFaction, "LEECH_1_MANUAL")
                    || (hasAbility(opponentFaction, "LEECH_1_MANUAL_WITH_PI")
                            && builtCount(state, opponentId, "PLANETARY_INSTITUTE") > 0);
            if (amount == 1 && !manualOne) {
                chargePower(opponent, 1); // 무비용 자동 수락 (아이타·타클론 PI는 수동 — edge-cases §4)
                continue;
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("from", builderId);
            context.put("amount", amount);
            context.put("vpCost", amount - 1);
            if (taklonsPi) {
                context.put("taklonsPi", true); // 수락 시 +1 토큰, 토큰/충전 순서 선택
            }
            toPush.add(new Decision(state.newDecisionId(), "LEECH_RESPONSE", opponentId, context));
        }
        for (int i = toPush.size() - 1; i >= 0; i--) {
            state.getDecisionStack().add(toPush.get(i));
        }
        List<Map<String, Object>> pushed = new ArrayList<>();
        for (Decision d : toPush) {
            pushed.add(Map.of("id", d.getId(), "type", d.getType(), "target", d.getTarget()));
        }
        return pushed;
    }

    private List<EngineEvent> applyLeechResponse(GameState state, Submit submit) {
        // 리치는 동시 응답 허용 — 스택 상단의 연속된 LEECH_RESPONSE 블록 안에서는 순서 무관 (✅확정 2026-07-23)
        Decision top = null;
        for (int i = state.getDecisionStack().size() - 1; i >= 0; i--) {
            Decision d = state.getDecisionStack().get(i);
            if (!"LEECH_RESPONSE".equals(d.getType())) {
                break;
            }
            if (d.getId().equals(submit.decisionId())) {
                top = d;
                break;
            }
        }
        if (top == null) {
            throw new EngineException("응답 가능한 리치 제안이 아닙니다: " + submit.decisionId());
        }
        if (!top.getTarget().equals(submit.playerId())) {
            throw new EngineException("이 결정의 대상 플레이어가 아닙니다");
        }
        boolean accepted = Boolean.TRUE.equals(submit.payload().get("accept"));
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);

        if (accepted) {
            int amount = (int) top.getContext().get("amount");
            if (Boolean.TRUE.equals(top.getContext().get("taklonsPi"))) {
                // 타클론 PI: +1 토큰, 토큰 먼저/충전 먼저 선택 (기본: 토큰 먼저)
                if ("CHARGE_FIRST".equals(submit.payload().get("order"))) {
                    chargePower(p, amount);
                    p.setBowl1(p.getBowl1() + 1);
                } else {
                    p.setBowl1(p.getBowl1() + 1);
                    chargePower(p, amount);
                }
            } else {
                chargePower(p, amount);
            }
            gainVp(p, -((int) top.getContext().get("vpCost")), "LEECH");
        }
        state.getDecisionStack().remove(top);

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("accept", accepted,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** 파워 순환: bowl1 → bowl2 → bowl3. 브레인스톤은 같은 볼의 일반 토큰보다 우선 이동 (edge-cases §1 확정) */
    static void chargePower(PlayerState p, int amount) {
        for (int i = 0; i < amount; i++) {
            if ("BOWL1".equals(p.getBrainstone())) {
                p.setBrainstone("BOWL2");
            } else if (p.getBowl1() > 0) {
                p.setBowl1(p.getBowl1() - 1);
                p.setBowl2(p.getBowl2() + 1);
            } else if ("BOWL2".equals(p.getBrainstone())) {
                p.setBrainstone("BOWL3");
            } else if (p.getBowl2() > 0) {
                p.setBowl2(p.getBowl2() - 1);
                p.setBowl3(p.getBowl3() + 1);
            } else {
                break;
            }
        }
    }

    static void spendPower(PlayerState p, int amount) {
        spendPower(p, amount, false, false);
    }

    static void spendPower(PlayerState p, int amount, boolean useBrainstone) {
        spendPower(p, amount, useBrainstone, false);
    }

    /**
     * 파워 사용: bowl3 → bowl1 복귀.
     * useBrainstone: 브레인스톤(bowl3)을 3파워 가치로 사용 — 잔여분 소멸, 사용 여부는 플레이어 선택 (확정).
     * nevlasDouble: 네블라 PI — bowl3 토큰 1개 = 2파워 (잔여 미반환).
     */
    static void spendPower(PlayerState p, int amount, boolean useBrainstone, boolean nevlasDouble) {
        if (useBrainstone) {
            if (!"BOWL3".equals(p.getBrainstone())) {
                throw new EngineException("브레인스톤이 bowl3에 없습니다");
            }
            p.setBrainstone("BOWL1");
            amount = Math.max(0, amount - 3);
        }
        int tokens = nevlasDouble ? (amount + 1) / 2 : amount;
        if (p.getBowl3() < tokens) {
            throw new EngineException("파워 부족 (bowl3: " + p.getBowl3() + " < " + tokens + ")");
        }
        p.setBowl3(p.getBowl3() - tokens);
        p.setBowl1(p.getBowl1() + tokens);
    }

    /** 가이아포밍: 토큰 amount개를 볼에서 가이아 구역으로 (bowl1 → 2 → 3 순, 브레인스톤 제외) */
    static void moveTokensToGaia(PlayerState p, int amount) {
        if (p.getBowl1() + p.getBowl2() + p.getBowl3() < amount) {
            throw new EngineException("파워 토큰 부족 (필요: " + amount + ")");
        }
        for (int i = 0; i < amount; i++) {
            if (p.getBowl1() > 0) {
                p.setBowl1(p.getBowl1() - 1);
            } else if (p.getBowl2() > 0) {
                p.setBowl2(p.getBowl2() - 1);
            } else {
                p.setBowl3(p.getBowl3() - 1);
            }
        }
        p.setGaiaPower(p.getGaiaPower() + amount);
    }

    static void removeTokens(PlayerState p, int amount) {
        removeTokens(p, amount, false);
    }

    /**
     * 토큰 영구 제거 (위성·인공물·함대 소각 비용): bowl1 → 2 → 3 순.
     * removeBrainstone: 브레인스톤을 제거분 1개로 포함 (✅확정 — 가능하되 FE 경고창 후 명시 선택으로만)
     */
    static void removeTokens(PlayerState p, int amount, boolean removeBrainstone) {
        if (removeBrainstone) {
            if (p.getBrainstone() == null || "GAIA".equals(p.getBrainstone())) {
                throw new EngineException("제거할 수 있는 브레인스톤이 없습니다");
            }
            p.setBrainstone(null); // 영구 제거
            amount = Math.max(0, amount - 1);
        }
        if (p.getBowl1() + p.getBowl2() + p.getBowl3() < amount) {
            throw new EngineException("파워 토큰 부족 (필요: " + amount + ")");
        }
        for (int i = 0; i < amount; i++) {
            if (p.getBowl1() > 0) {
                p.setBowl1(p.getBowl1() - 1);
            } else if (p.getBowl2() > 0) {
                p.setBowl2(p.getBowl2() - 1);
            } else {
                p.setBowl3(p.getBowl3() - 1);
            }
        }
    }

    /**
     * 건물 파워값 — 리치·연방이 공유하는 단일 함수 (edge-cases §2: v1의 경로별 불일치 버그 방지).
     * 보정: BASIC_TILE_9(+1, PI/아카데미), 매드 안드로이드 PI+티타늄(+1), 모웨이드 링(+2).
     * 란티다 기생 광산은 헥스에 상대 건물과 공존하므로 호출부에서 고정 1로 합산한다.
     */
    private int buildingPowerValue(GameState state, String ownerId, HexState hex) {
        int base = data.tiles().get("federationSetup").get("buildingPowerValues")
                .path(hex.getBuildingType()).asInt(0);
        if (base == 0) {
            return 0;
        }
        PlayerState owner = state.player(ownerId);
        boolean bigBuilding = "PLANETARY_INSTITUTE".equals(hex.getBuildingType()) || "ACADEMY".equals(hex.getBuildingType());
        if (bigBuilding && owner.getTechTiles().contains("BASIC_TILE_9")
                && !owner.getCoveredTechTiles().contains("BASIC_TILE_9")) {
            base += 1;
        }
        if ("TITANIUM".equals(hex.getPlanet())
                && hasAbility(data.faction(owner.getFaction()), "PI_TITANIUM_POWER_PLUS_1")
                && builtCount(state, ownerId, "PLANETARY_INSTITUTE") > 0) {
            base += 1;
        }
        if (hex.isRing()) {
            base += 2; // 모웨이드 링
        }
        return base;
    }

    /**
     * 연방 자동 편입 — 새 건물이 자기 연방 그룹에 인접(또는 그 위 기생)하면 그룹에 추가
     * (edge-cases §8: 파워 재계산만, 추가 보상 없음).
     */
    @SuppressWarnings("unchecked")
    private void autoIncorporateIntoFederation(GameState state, String playerId, HexCoord built) {
        for (Map<String, Object> group : state.player(playerId).getFederations()) {
            List<String> buildings = (List<String>) group.get("buildings");
            List<String> satellites = (List<String>) group.get("satellites");
            boolean adjacent = false;
            for (String key : buildings) {
                adjacent |= HexCoord.parse(key).distance(built) <= 1;
            }
            for (String key : satellites) {
                adjacent |= HexCoord.parse(key).distance(built) <= 1;
            }
            if (adjacent) {
                if (!buildings.contains(built.key())) {
                    buildings.add(built.key());
                }
                return;
            }
        }
    }

    private void requireNotNearExistingFederation(String key, List<String> existingKeys) {
        HexCoord c = HexCoord.parse(key);
        for (String existing : existingKeys) {
            if (HexCoord.parse(existing).distance(c) <= 1) {
                throw new EngineException("기존 연방에 포함되거나 인접한 헥스입니다: " + key);
            }
        }
    }

    /** 선택된 헥스 집합(건물+위성)이 하나의 연결 그룹인지 BFS 검증 */
    private void requireConnected(List<String> buildings, List<String> satellites) {
        Set<String> all = new HashSet<>(buildings);
        all.addAll(satellites);
        if (!isConnected(all, buildings.get(0))) {
            throw new EngineException("연방 구성 요소가 연결되어 있지 않습니다");
        }
    }

    /**
     * 위성 최소성 검증 — 어떤 위성이든 빼도 연결이 유지되면 과다 사용 (edge-cases §2의 v1 과다 계산 버그 방지).
     * 완전한 Steiner 최소성은 아니지만 불필요 위성을 차단하는 근사.
     */
    private void requireNoRedundantSatellites(List<String> buildings, List<String> satellites) {
        Set<String> all = new HashSet<>(buildings);
        all.addAll(satellites);
        for (String satellite : satellites) {
            Set<String> without = new HashSet<>(all);
            without.remove(satellite);
            if (isConnected(without, buildings.get(0))) {
                throw new EngineException("불필요한 위성이 포함되어 있습니다: " + satellite);
            }
        }
    }

    private boolean isConnected(Set<String> all, String anchor) {
        Set<String> visited = new HashSet<>();
        List<String> frontier = new ArrayList<>(List.of(anchor));
        visited.add(anchor);
        while (!frontier.isEmpty()) {
            HexCoord current = HexCoord.parse(frontier.remove(frontier.size() - 1));
            for (String key : all) {
                if (!visited.contains(key) && current.distance(HexCoord.parse(key)) == 1) {
                    visited.add(key);
                    frontier.add(key);
                }
            }
        }
        return visited.size() == all.size();
    }

    // ═══════════════ 연방 토큰 / 자원 획득 / 라운드 점수 ═══════════════

    private void grantFederationTile(GameState state, String playerId, String tileId) {
        PlayerState p = state.player(playerId);
        JsonNode tile = findTile(data.tiles().get("federationTiles"), tileId);
        p.getFederationTokens().add(tileId);
        if (!tile.path("usable").asBoolean(true)) {
            p.getUsedFederationTokens().add(tileId);
        }
        applyFederationTileEffects(state, playerId, tile);
        roundScore(state, p, "FEDERATION_FORMED", 1);
    }

    /** 연방 타일의 보상·특수 효과 — 최초 획득과 재수령(TWILIGHT_FED, ARTIFACT_13)이 공유 */
    private void applyFederationTileEffects(GameState state, String playerId, JsonNode tile) {
        if (tile.has("gain")) {
            gainResources(state, playerId, tile.get("gain"), "FEDERATION");
        }
        switch (tile.path("special").asText("")) {
            case "GAIN_TECH_TILE" -> pushDecision(state, "CHOOSE_TECH_TILE", playerId, Map.of());
            case "TERRAFORM_3_PLACE_MINE" -> pushFreeMine(state, playerId, 3, true, 0, null);
            case "PLACE_MINE_NO_RANGE_LIMIT" -> pushFreeMine(state, playerId, 0, true, 99, null);
            default -> { }
        }
    }

    private Map<String, Object> pushFreeMine(GameState state, String playerId,
                                             int freeShovels, boolean freeBuild, int rangeBonus, String planetOnly) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("freeShovels", freeShovels);
        context.put("freeBuild", freeBuild);
        context.put("rangeBonus", rangeBonus);
        if (planetOnly != null) {
            context.put("planetOnly", planetOnly);
        }
        return pushDecision(state, "PLACE_MINE", playerId, context);
    }

    private boolean hasUsableFederationToken(PlayerState p) {
        return p.getFederationTokens().stream()
                .anyMatch(id -> occurrences(p.getFederationTokens(), id) > occurrences(p.getUsedFederationTokens(), id));
    }

    /** 덮이지 않은 보유 기술 타일 — 패시브 효과 적용 여부 */
    private static boolean hasActiveTile(PlayerState p, String tileId) {
        return p.getTechTiles().contains(tileId) && !p.getCoveredTechTiles().contains(tileId);
    }

    /** 광산 건설 기술 타일 보너스: ADV_TILE_16(건설마다 +3VP), BASIC_TILE_8(가이아 행성 +3VP) */
    private static void techMineBuildVp(PlayerState p, boolean gaia) {
        if (hasActiveTile(p, "ADV_TILE_16")) {
            gainVp(p, 3, "TECH_ADV");
        }
        if (gaia && hasActiveTile(p, "BASIC_TILE_8")) {
            gainVp(p, 3, "TECH_BASIC");
        }
    }

    /** 교역소 건설 기술 타일 보너스: ADV_TILE_17(건설마다 +3VP) */
    private static void techTsBuildVp(PlayerState p) {
        if (hasActiveTile(p, "ADV_TILE_17")) {
            gainVp(p, 3, "TECH_ADV");
        }
    }

    private void flipUsableFederationToken(PlayerState p) {
        for (String id : p.getFederationTokens()) {
            if (occurrences(p.getFederationTokens(), id) > occurrences(p.getUsedFederationTokens(), id)) {
                p.getUsedFederationTokens().add(id);
                return;
            }
        }
        throw new EngineException("사용 가능한 연방 토큰이 없습니다");
    }

    private static int occurrences(List<String> list, String id) {
        return (int) list.stream().filter(id::equals).count();
    }

    private void gainResources(GameState state, String playerId, JsonNode gain, String vpCategory) {
        PlayerState p = state.player(playerId);
        JsonNode faction = faction(state, playerId);
        p.setCredits(p.getCredits() + gain.path("credits").asInt(0));
        p.setOre(p.getOre() + gain.path("ore").asInt(0));
        p.setKnowledge(p.getKnowledge() + gain.path("knowledge").asInt(0));
        gainVp(p, gain.path("vp").asInt(0), vpCategory);
        p.setBowl1(p.getBowl1() + gain.path("powerTokens").asInt(0));
        p.setBowl3(p.getBowl3() + gain.path("powerTokensBowl3").asInt(0));
        int qic = gain.path("qic").asInt(0);
        if (qic > 0) {
            if (hasAbility(faction, "QIC_TO_ORE_UNTIL_QIC_ACADEMY") && !hasQicAcademy(state, playerId)) {
                p.setOre(p.getOre() + qic);
            } else {
                p.setQic(p.getQic() + qic);
            }
        }
        int gaiaformer = gain.path("gaiaformer").asInt(0);
        if (gaiaformer > 0) {
            p.getStock().merge("GAIAFORMER", gaiaformer, Integer::sum);
        }
        int charge = gain.path("powerCharge").asInt(0);
        if (charge > 0) {
            chargePower(p, charge);
        }
    }

    /** 현재 라운드 점수 타일이 이벤트와 일치하면 VP 지급 */
    private void roundScore(GameState state, PlayerState p, String event, int count) {
        int round = state.getRound();
        if (round < 1 || round > state.getBoard().getRoundScoringTiles().size()) {
            return;
        }
        String tileId = state.getBoard().getRoundScoringTiles().get(round - 1);
        JsonNode tile = findTile(data.tiles().get("roundScoringTiles"), tileId);
        if (event.equals(tile.get("event").asText())) {
            gainVp(p, tile.get("vp").asInt() * count, "ROUND_" + round); // 라운드별 세분화 (J-12)
        }
    }

    // ═══════════════ 판정 헬퍼 ═══════════════

    /** 링 순환 거리 기반 삽 수 (종족 고정 삽·확장 행성 규칙 반영) */
    private int terraformShovels(PlayerState p, JsonNode faction, String targetPlanet) {
        String home = faction.get("homePlanet").asText();
        // 확장 종족은 홈 행성이 없다 (표기 행성 = 초기 배치용 시작 행성) — 0삽 행성 없음
        if (!faction.path("expansion").asBoolean(false) && home.equals(targetPlanet)) {
            return 0;
        }
        if ("TRANSCENDENT".equals(targetPlanet) || "BLACK_PLANET".equals(targetPlanet)) {
            return 3;
        }
        if (hasAbility(faction, "TERRAFORM_FIXED_1")) {
            return 1;
        }
        if (hasAbility(faction, "TERRAFORM_FIXED_2")) {
            return 2;
        }
        if (hasAbility(faction, "TERRAFORM_HOME_3_OTHERS_1")) {
            return p.getThreeShovelPlanets().contains(targetPlanet) ? 3 : 1;
        }
        return ringDistance(home, targetPlanet);
    }

    private int ringDistance(String from, String to) {
        JsonNode ring = data.constants().get("terraformRing");
        int a = -1;
        int b = -1;
        for (int i = 0; i < ring.size(); i++) {
            if (ring.get(i).asText().equals(from)) {
                a = i;
            }
            if (ring.get(i).asText().equals(to)) {
                b = i;
            }
        }
        if (a < 0 || b < 0) {
            throw new EngineException("테라포밍 링에 없는 행성: " + from + " → " + to);
        }
        int d = Math.abs(a - b);
        return Math.min(d, ring.size() - d);
    }

    private void checkRange(GameState state, String playerId, HexCoord target, PlayerState p,
                            int qicForRange, int rangeBonus) {
        int range = data.tech().get("navRangeByLevel").get(p.track("NAVIGATION")).asInt()
                + qicForRange * 2 + rangeBonus
                + (hasActiveTile(p, "BASIC_EXP_TILE_1") ? 1 : 0); // 확장 기본 타일: 기본 거리 +1
        int best = Integer.MAX_VALUE;
        for (Map.Entry<String, HexState> e : state.getHexes().entrySet()) {
            if (playerId.equals(e.getValue().getBuildingOwner())
                    || playerId.equals(e.getValue().getParasiteOwner())) {
                best = Math.min(best, HexCoord.parse(e.getKey()).distance(target));
            }
        }
        if (best == Integer.MAX_VALUE) {
            throw new EngineException("보유 건물이 없습니다");
        }
        if (best > range) {
            throw new EngineException("항해 거리 밖입니다 (거리 " + best + " > 범위 " + range + ")");
        }
    }

    private boolean hasOpponentBuildingNear(GameState state, String playerId, HexCoord target, int radius) {
        for (Map.Entry<String, HexState> e : state.getHexes().entrySet()) {
            HexState h = e.getValue();
            if (h.hasBuilding() && !playerId.equals(h.getBuildingOwner())
                    && HexCoord.parse(e.getKey()).distance(target) <= radius) {
                return true;
            }
        }
        return false;
    }

    private boolean hasQicAcademy(GameState state, String playerId) {
        return state.getHexes().values().stream().anyMatch(h ->
                playerId.equals(h.getBuildingOwner()) && "ACADEMY".equals(h.getBuildingType())
                        && "QIC".equals(h.getAcademyType()));
    }

    // ═══════════════ 카운트 헬퍼 ═══════════════

    private int builtCount(GameState state, String playerId, String buildingType) {
        long count = state.getHexes().values().stream()
                .filter(h -> playerId.equals(h.getBuildingOwner()) && buildingType.equals(h.getBuildingType()))
                .count();
        if ("MINE".equals(buildingType)) { // 란티다 기생 광산은 광산 수(수입·타일 점수)에 포함
            count += state.getHexes().values().stream()
                    .filter(h -> playerId.equals(h.getParasiteOwner())).count();
        }
        return (int) count;
    }

    private int gaiaPlanetCount(GameState state, String playerId) {
        return (int) state.getHexes().values().stream()
                .filter(h -> hasBuildingOf(h, playerId) && "GAIA".equals(h.getPlanet()))
                .count();
    }

    private Set<String> colonizedPlanetTypes(GameState state, String playerId) {
        Set<String> types = new HashSet<>();
        for (HexState h : state.getHexes().values()) {
            if (playerId.equals(h.getBuildingOwner()) && h.hasBuilding() && !"EMPTY".equals(h.getPlanet())
                    && !"GAIAFORMER".equals(h.getBuildingType())) { // 포머 배치만으로는 개척 아님
                types.add(h.getPlanet());
            }
        }
        return types;
    }

    /** 이 섹터에 내 구조물(건물·기생 광산, 가이아포머 제외)이 이미 있는가 — 새 섹터 진입 판정 */
    private boolean hasPresenceInSector(GameState state, String playerId, String sectorId) {
        for (HexState h : state.getHexes().values()) {
            if (!sectorId.equals(h.getSectorId())) {
                continue;
            }
            if ((playerId.equals(h.getBuildingOwner()) && !"GAIAFORMER".equals(h.getBuildingType()))
                    || playerId.equals(h.getParasiteOwner())) {
                return true;
            }
        }
        return false;
    }

    /** 새 섹터 진입·새 행성 종류 개척 트리거 (라운드 점수 + 기오덴·다카니안 PI 패시브) — 커밋 후 호출 */
    private void newColonizationTriggers(GameState state, String playerId, boolean newSector, boolean newPlanetType) {
        PlayerState p = state.player(playerId);
        JsonNode faction = data.faction(p.getFaction());
        if (newSector) {
            roundScore(state, p, "NEW_SECTOR_ENTERED", 1);
            if (hasAbility(faction, "PI_NEW_SECTOR_MINE_BONUS")
                    && builtCount(state, playerId, "PLANETARY_INSTITUTE") > 0) {
                p.setCredits(p.getCredits() + 2); // 다카니안 PI: 새 섹터 광산 +2크레딧 +1지식
                p.setKnowledge(p.getKnowledge() + 1);
            }
        }
        if (newPlanetType) {
            roundScore(state, p, "NEW_PLANET_TYPE_COLONIZED", 1);
            if (hasAbility(faction, "PI_NEW_PLANET_TYPE_KNOWLEDGE_3")
                    && builtCount(state, playerId, "PLANETARY_INSTITUTE") > 0) {
                p.setKnowledge(p.getKnowledge() + 3); // 기오덴 PI: 새 행성 타입 개척 +3지식
            }
        }
    }

    /** 건물 보유 판정 — 란티다 기생 광산 포함, 가이아포머 제외 (포머는 건물이 아님) */
    private static boolean hasBuildingOf(HexState h, String playerId) {
        return (playerId.equals(h.getBuildingOwner()) && !"GAIAFORMER".equals(h.getBuildingType()))
                || playerId.equals(h.getParasiteOwner());
    }

    private int buildingsInBaseSectors(GameState state, String playerId) {
        return (int) state.getHexes().values().stream()
                .filter(h -> hasBuildingOf(h, playerId) && h.getSectorId().startsWith("SECTOR_"))
                .count();
    }

    private int deepSectorsWithBuilding(GameState state, String playerId) {
        Set<String> sectors = new HashSet<>();
        for (HexState h : state.getHexes().values()) {
            if (hasBuildingOf(h, playerId) && h.getSectorId().startsWith("DEEP_")) {
                sectors.add(h.getSectorId());
            }
        }
        return sectors.size();
    }

    private int deepSectorBuildings(GameState state, String playerId) {
        return (int) state.getHexes().values().stream()
                .filter(h -> hasBuildingOf(h, playerId) && h.getSectorId().startsWith("DEEP_"))
                .count();
    }

    private int asteroidBuildings(GameState state, String playerId) {
        return (int) state.getHexes().values().stream()
                .filter(h -> hasBuildingOf(h, playerId) && "ASTEROIDS".equals(h.getPlanet()))
                .count()
                + (state.player(playerId).getArtifacts().contains("ARTIFACT_7") ? 1 : 0); // ⑦ 가상 소행성 광산 포함
    }

    // ═══════════════ 공통 헬퍼 ═══════════════

    private void advanceTurn(GameState state) {
        List<String> order = state.getTurnOrder();
        int idx = order.indexOf(state.getActivePlayer());
        for (int step = 1; step <= order.size(); step++) {
            String next = order.get((idx + step) % order.size());
            if (!state.player(next).isPassed()) {
                state.setActivePlayer(next);
                return;
            }
        }
        finishRound(state); // 전원 패스 → 라운드 종료
    }

    /** 최종 점수 또는 다음 라운드 진입 (가이아 페이즈 → 수입 페이즈, decision-flows §3) */
    private void finishRound(GameState state) {
        if (state.getRound() >= 6) {
            finalScoring(state);
            state.setPhase("FINISHED");
            return;
        }
        state.setRound(state.getRound() + 1);
        state.setTurnOrder(new ArrayList<>(state.getBoard().getPassOrder()));
        state.getBoard().getPassOrder().clear();
        state.getBoard().getPowerActionsUsedThisRound().clear();
        for (PlayerState p : state.getPlayers().values()) {
            p.setPassed(false);
            p.getUsedSpecialActions().clear();
            if (p.getBaltaksConvertedFormers() > 0) { // 발타크: 변환된 포머 반환
                p.getStock().merge("GAIAFORMER", p.getBaltaksConvertedFormers(), Integer::sum);
                p.setBaltaksConvertedFormers(0);
            }
        }
        incomePhase(state);       // 라운드 진입 순서: 수입 → 가이아 → 종족 능력 (✅확정 2026-07-22)
        gaiaPhase(state);
        pushAbilityPhase(state);  // 종족 능력 — 새 턴 순서(패스 순서)대로 해소
        state.setActivePlayer(state.getTurnOrder().get(0));
    }

    // ═══════════════ 가이아 페이즈 ═══════════════

    /** ① 가이아포머가 있는 차원 변형 → 가이아 변환 ② 가이아 구역 파워 복귀 (테란 bowl2 + PI 배분 결정) */
    private void gaiaPhase(GameState state) {
        for (HexState hex : state.getHexes().values()) {
            if ("TRANSDIM".equals(hex.getPlanet()) && "GAIAFORMER".equals(hex.getBuildingType())) {
                hex.setPlanet("GAIA");
            }
        }
        for (Map.Entry<String, PlayerState> e : state.getPlayers().entrySet()) {
            PlayerState p = e.getValue();
            // 브레인스톤 복귀 (함대 입장 등으로 가이아 구역에 있던 경우)
            if ("GAIA".equals(p.getBrainstone())) {
                p.setBrainstone("BOWL1");
            }
            if (p.getGaiaPower() <= 0) {
                continue;
            }
            JsonNode faction = data.faction(p.getFaction());
            // 아이타 PI(4개 이상): 종족 능력 페이즈의 ITARS_GAIA_TECH에서 처리 (잔여분 bowl1 복귀 포함)
            if (hasAbility(faction, "PI_GAIA_4_TO_TECH_TILE")
                    && builtCount(state, e.getKey(), "PLANETARY_INSTITUTE") > 0
                    && p.getGaiaPower() >= 4) {
                continue;
            }
            if (hasAbility(faction, "GAIA_POWER_RETURN_BOWL2")) {
                if (hasAbility(faction, "PI_GAIA_TOKEN_CONVERT")
                        && builtCount(state, e.getKey(), "PLANETARY_INSTITUTE") > 0) {
                    state.getDecisionStack().add(new Decision(state.newDecisionId(),
                            "TERRANS_GAIA_CONVERT", e.getKey(), Map.of("tokens", p.getGaiaPower())));
                    continue; // 배분 결정 후 잔여분 bowl2 복귀
                }
                p.setBowl2(p.getBowl2() + p.getGaiaPower());
            } else {
                p.setBowl1(p.getBowl1() + p.getGaiaPower());
            }
            p.setGaiaPower(0);
        }
    }

    /** 테란 PI: 가이아 토큰 → 자원 배분 (1토큰=1c, 3토큰=1o, 4토큰=1q, 4토큰=1k), 잔여 bowl2 복귀 */
    private List<EngineEvent> applyTerransGaiaConvert(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "TERRANS_GAIA_CONVERT");
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);

        int credits = intOf(submit.payload(), "credits");
        int ore = intOf(submit.payload(), "ore");
        int qic = intOf(submit.payload(), "qic");
        int knowledge = intOf(submit.payload(), "knowledge");
        int cost = credits + ore * 3 + qic * 4 + knowledge * 4;
        int tokens = (int) top.getContext().get("tokens");
        if (cost > tokens) {
            throw new EngineException("가이아 토큰 부족 (필요 " + cost + " > 보유 " + tokens + ")");
        }
        p.setCredits(p.getCredits() + credits);
        p.setOre(p.getOre() + ore);
        p.setQic(p.getQic() + qic);
        p.setKnowledge(p.getKnowledge() + knowledge);
        p.setBowl2(p.getBowl2() + (tokens - cost));
        p.setGaiaPower(0);
        state.getDecisionStack().remove(top);

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** 아이타 PI: 가이아 구역 파워 4개당 기술 타일 1개 — 기본/고급 무관 (희생 수 선택), 잔여분 bowl1 복귀 */
    private List<EngineEvent> applyItarsGaiaTech(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "ITARS_GAIA_TECH");
        PlayerState p = state.player(submit.playerId());
        int tokens = (int) top.getContext().get("tokens");
        int sacrifice = intOf(submit.payload(), "sacrificeCount");
        if (sacrifice < 0 || sacrifice * 4 > tokens) {
            throw new EngineException("가이아 토큰 부족 (요청 " + sacrifice + "×4 > 보유 " + tokens + ")");
        }
        state.getDecisionStack().remove(top);
        p.setGaiaPower(0);
        p.setBowl1(p.getBowl1() + tokens - sacrifice * 4); // 희생분은 영구 제거
        for (int i = 0; i < sacrifice; i++) {
            pushDecision(state, "CHOOSE_TECH_TILE", submit.playerId(), Map.of());
        }
        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("sacrificed", sacrifice * 4, "techTiles", sacrifice), List.of()));
    }

    // ═══════════════ 수입 페이즈 ═══════════════

    private record Income(int credits, int ore, int knowledge, int qic, int vp, int charge, int tokens) {}

    private void incomePhase(GameState state) {
        for (Map.Entry<String, PlayerState> e : state.getPlayers().entrySet()) {
            String playerId = e.getKey();
            PlayerState p = e.getValue();
            Income income = computeIncome(state, playerId);

            Map<String, Object> gains = new LinkedHashMap<>();
            gains.put("credits", income.credits());
            gains.put("ore", income.ore());
            gains.put("knowledge", income.knowledge());
            gains.put("vp", income.vp());
            gainResources(state, playerId, toNode(gains), "INCOME");
            addQic(state, playerId, income.qic());

            if (income.charge() > 0 && income.tokens() > 0 && powerOrderMatters(p, income)) {
                state.getDecisionStack().add(new Decision(state.newDecisionId(), "INCOME_POWER_ORDER",
                        playerId, Map.of("powerCharge", income.charge(), "powerTokens", income.tokens())));
            } else {
                p.setBowl1(p.getBowl1() + income.tokens()); // 기본: 토큰 먼저
                chargePower(p, income.charge());
            }
        }
    }

    /** 순서가 결과를 바꾸는 경우에만 결정 발생 (확정: 아니면 자동 적용) */
    private boolean powerOrderMatters(PlayerState p, Income income) {
        int[] tokensFirst = simulatePower(p, income.tokens(), income.charge(), true);
        int[] chargeFirst = simulatePower(p, income.tokens(), income.charge(), false);
        return !java.util.Arrays.equals(tokensFirst, chargeFirst);
    }

    private int[] simulatePower(PlayerState p, int tokens, int charge, boolean tokensFirst) {
        PlayerState copy = new PlayerState();
        copy.setBowl1(p.getBowl1());
        copy.setBowl2(p.getBowl2());
        copy.setBowl3(p.getBowl3());
        if (tokensFirst) {
            copy.setBowl1(copy.getBowl1() + tokens);
            chargePower(copy, charge);
        } else {
            chargePower(copy, charge);
            copy.setBowl1(copy.getBowl1() + tokens);
        }
        return new int[]{copy.getBowl1(), copy.getBowl2(), copy.getBowl3()};
    }

    private List<EngineEvent> applyIncomePowerOrder(GameState state, Submit submit) {
        Decision top = requireTopDecision(state, submit, "INCOME_POWER_ORDER");
        PlayerState p = state.player(submit.playerId());
        Map<String, Object> before = resourceSnapshot(p);
        int charge = (int) top.getContext().get("powerCharge");
        int tokens = (int) top.getContext().get("powerTokens");
        String order = (String) submit.payload().getOrDefault("order", "TOKENS_FIRST");

        if ("CHARGE_FIRST".equals(order)) {
            chargePower(p, charge);
            p.setBowl1(p.getBowl1() + tokens);
        } else {
            p.setBowl1(p.getBowl1() + tokens);
            chargePower(p, charge);
        }
        state.getDecisionStack().remove(top);

        return List.of(event("DECISION_RESOLVED", submit,
                Map.of("order", order,
                        "resources", Map.of(submit.playerId(), diff(before, resourceSnapshot(p)))), List.of()));
    }

    /** 종족 기본 + 건물 + PI + 기술 타일 + 부스터 + 경제/지식 트랙 수입 합산 */
    private Income computeIncome(GameState state, String playerId) {
        PlayerState p = state.player(playerId);
        JsonNode faction = data.faction(p.getFaction());
        JsonNode buildingIncome = data.constants().get("buildingIncome");
        boolean bescods = hasAbility(faction, "SWAP_TS_LAB_INCOME");
        boolean nevlas = hasAbility(faction, "LAB_INCOME_POWERCHARGE_2");

        int credits = 0;
        int ore = 0;
        int knowledge = 0;
        int qic = 0;
        int vp = 0;
        int charge = 0;
        int tokens = 0;

        // 종족 기본 수입
        JsonNode base = faction.has("baseIncome") ? faction.get("baseIncome")
                : toNode(Map.of("ore", 1, "knowledge", 1));
        credits += base.path("credits").asInt(0);
        ore += base.path("ore").asInt(0);
        knowledge += base.path("knowledge").asInt(0);
        tokens += base.path("powerToken").asInt(0);

        // 건물 수입
        int mines = Math.min(builtCount(state, playerId, "MINE"), 8);
        ore += buildingIncome.get("mineOre").get(mines).asInt();
        int ts = Math.min(builtCount(state, playerId, "TRADING_STATION"), 4);
        if (bescods) {
            knowledge += ts;
        } else {
            credits += buildingIncome.get("tradingStationCredits").get(ts).asInt();
        }
        int labs = Math.min(builtCount(state, playerId, "RESEARCH_LAB"), 3);
        if (bescods) {
            credits += new int[]{0, 3, 7, 12}[labs];
        } else if (nevlas) {
            charge += 2 * labs;
        } else {
            knowledge += buildingIncome.get("researchLabKnowledge").get(labs).asInt();
        }
        int knowledgeAcademies = (int) state.getHexes().values().stream()
                .filter(h -> playerId.equals(h.getBuildingOwner()) && "ACADEMY".equals(h.getBuildingType())
                        && "KNOWLEDGE".equals(h.getAcademyType()))
                .count();
        knowledge += knowledgeAcademies * (hasAbility(faction, "KNOWLEDGE_ACADEMY_INCOME_3") ? 3 : 2);
        if (builtCount(state, playerId, "PLANETARY_INSTITUTE") > 0) {
            JsonNode pi = faction.has("piIncome") ? faction.get("piIncome")
                    : toNode(Map.of("powerCharge", 4, "powerToken", 1));
            charge += pi.path("powerCharge").asInt(0);
            tokens += pi.path("powerToken").asInt(0);
            qic += pi.path("qic").asInt(0);
            ore += pi.path("ore").asInt(0);
        }

        // 기술 타일 (덮이지 않은 INCOME 타일)
        for (String tileId : p.getTechTiles()) {
            if (p.getCoveredTechTiles().contains(tileId) || tileId.startsWith("ADV_")) {
                continue;
            }
            JsonNode tile = findTile(data.tech().get("basicTiles"), tileId);
            if (tile.has("income")) {
                JsonNode inc = tile.get("income");
                credits += inc.path("credits").asInt(0);
                ore += inc.path("ore").asInt(0);
                knowledge += inc.path("knowledge").asInt(0);
                charge += inc.path("powerCharge").asInt(0);
            }
        }

        // 부스터
        if (p.getBooster() != null) {
            JsonNode inc = findTile(data.tiles().get("boosters"), p.getBooster()).get("income");
            credits += inc.path("credits").asInt(0);
            ore += inc.path("ore").asInt(0);
            knowledge += inc.path("knowledge").asInt(0);
            qic += inc.path("qic").asInt(0);
            charge += inc.path("powerCharge").asInt(0);
            tokens += inc.path("powerTokens").asInt(0);
        }

        // 경제/지식 트랙 (레벨 5 = 수입 소멸)
        int economy = p.track("ECONOMY");
        if (economy >= 1 && economy <= 4) {
            JsonNode byLevel = data.tech().get("tracks").get("ECONOMY").get("incomeByLevel").get(String.valueOf(economy));
            JsonNode inc = byLevel.has("A") ? byLevel.get(state.getBoard().getEconomyOption()) : byLevel;
            credits += inc.path("credits").asInt(0);
            ore += inc.path("ore").asInt(0);
            vp += inc.path("vp").asInt(0);
            charge += inc.path("powerCharge").asInt(0);
        }
        int science = p.track("SCIENCE");
        if (science >= 1 && science <= 4) {
            knowledge += data.tech().get("tracks").get("SCIENCE").get("incomeByLevel")
                    .get(String.valueOf(science)).get("knowledge").asInt();
        }

        // 인공물 수입 (ARTIFACT_4: bowl3 토큰 2 — 순서 결정 대상 아님, ARTIFACT_5: 광석 1 + 지식 1)
        for (String artifactId : p.getArtifacts()) {
            JsonNode artifact = findTile(data.actions().get("artifacts"), artifactId);
            if (artifact.has("income")) {
                JsonNode inc = artifact.get("income");
                ore += inc.path("ore").asInt(0);
                knowledge += inc.path("knowledge").asInt(0);
                p.setBowl3(p.getBowl3() + inc.path("powerTokensBowl3").asInt(0));
            }
        }

        return new Income(credits, ore, knowledge, qic, vp, charge, tokens);
    }

    private void addQic(GameState state, String playerId, int amount) {
        if (amount <= 0) {
            return;
        }
        gainResources(state, playerId, toNode(Map.of("qic", amount)), "GAIN");
    }

    private JsonNode toNode(Map<String, Object> map) {
        return tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(map);
    }

    // ═══════════════ 최종 점수 ═══════════════

    /** 최종 점수: 순위 타일 2종(18/12/6, 동점 분배) + 트랙 레벨 3+ 칸당 4VP + 잔여 자원 3개당 1VP − 비딩값 */
    private void finalScoring(GameState state) {
        for (String tileId : state.getBoard().getFinalScoringTiles()) {
            String metric = findTile(data.tiles().get("finalScoringTiles"), tileId).get("metric").asText();
            awardRankVp(state, metric);
        }
        JsonNode rankConfig = data.tiles().get("finalScoring");
        int vpPerLevel = rankConfig.get("knowledgeTrackLevel3PlusVpPerLevel").asInt();
        int resourcesPerVp = rankConfig.get("resourcesPerVp").asInt();

        for (Map.Entry<String, PlayerState> e : state.getPlayers().entrySet()) {
            PlayerState p = e.getValue();
            for (int level : p.getTracks().values()) {
                if (level >= 3) {
                    gainVp(p, vpPerLevel * (level - 2), "FINAL_TRACK"); // 레벨 3 이상 칸당
                }
            }
            boolean nevlasPi = hasAbility(data.faction(p.getFaction()), "PI_BOWL3_DOUBLE_VALUE")
                    && builtCount(state, e.getKey(), "PLANETARY_INSTITUTE") > 0;
            int bowl3 = p.getBowl3() * (nevlasPi ? 2 : 1) + ("BOWL3".equals(p.getBrainstone()) ? 1 : 0);
            int resources = p.getCredits() + p.getOre() + p.getKnowledge() + p.getQic() + bowl3;
            gainVp(p, resources / resourcesPerVp, "FINAL_RESOURCES");
            gainVp(p, -p.getBidVp(), "BID"); // 종족 비딩값 차감 (decision-flows §4)
        }
        assignFinalRanks(state);
    }

    /** 최종 순위: 총점 내림차순 — 동률이면 비딩값이 적은 쪽 승리, 총점·비딩 모두 같으면 공동 순위 (J-10) */
    static void assignFinalRanks(GameState state) {
        List<PlayerState> ranking = new ArrayList<>(state.getPlayers().values());
        ranking.sort((a, b) -> {
            int byVp = Integer.compare(b.getVp(), a.getVp());
            return byVp != 0 ? byVp : Integer.compare(a.getBidVp(), b.getBidVp());
        });
        int rank = 0;
        PlayerState prev = null;
        for (int i = 0; i < ranking.size(); i++) {
            PlayerState p = ranking.get(i);
            if (prev == null || p.getVp() != prev.getVp() || p.getBidVp() != prev.getBidVp()) {
                rank = i + 1;
            }
            p.setFinalRank(rank);
            prev = p;
        }
    }

    private void awardRankVp(GameState state, String metric) {
        for (Map.Entry<String, Integer> e : rankVpFor(state, metric).entrySet()) {
            gainVp(state.player(e.getKey()), e.getValue(), "FINAL_RANK");
        }
    }

    /** 최종 타일 순위 VP 산출 (적용 없음 — awardRankVp·finalScorePreview 공용) */
    private Map<String, Integer> rankVpFor(GameState state, String metric) {
        List<Map.Entry<String, Integer>> scores = new ArrayList<>();
        for (String playerId : state.getPlayers().keySet()) {
            scores.add(Map.entry(playerId, finalMetric(state, playerId, metric)));
        }
        scores.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        JsonNode rankVp = data.tiles().get("finalScoring").get("rankVp");

        Map<String, Integer> result = new LinkedHashMap<>();
        int position = 0;
        while (position < scores.size()) {
            int value = scores.get(position).getValue();
            int groupEnd = position;
            while (groupEnd + 1 < scores.size() && scores.get(groupEnd + 1).getValue() == value) {
                groupEnd++;
            }
            if (value > 0) { // 달성도 0이면 순위 VP 없음
                int sum = 0;
                for (int r = position; r <= groupEnd; r++) {
                    sum += r < rankVp.size() ? rankVp.get(r).asInt() : 0;
                }
                int share = sum / (groupEnd - position + 1);
                for (int r = position; r <= groupEnd; r++) {
                    result.merge(scores.get(r).getKey(), share, Integer::sum);
                }
            }
            position = groupEnd + 1;
        }
        return result;
    }

    /** 최종 점수 미리보기 — 현재 상태 기준 예상치 (상태 무변경, FE 점수창 실시간 표시용) */
    public Map<String, Map<String, Integer>> finalScorePreview(GameState state) {
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (String tileId : state.getBoard().getFinalScoringTiles()) {
            String metric = findTile(data.tiles().get("finalScoringTiles"), tileId).get("metric").asText();
            rankVpFor(state, metric).forEach((pid, vp) -> rank.merge(pid, vp, Integer::sum));
        }
        JsonNode rankConfig = data.tiles().get("finalScoring");
        int vpPerLevel = rankConfig.get("knowledgeTrackLevel3PlusVpPerLevel").asInt();
        int resourcesPerVp = rankConfig.get("resourcesPerVp").asInt();

        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerState> e : state.getPlayers().entrySet()) {
            PlayerState p = e.getValue();
            int track = 0;
            for (int level : p.getTracks().values()) {
                if (level >= 3) {
                    track += vpPerLevel * (level - 2);
                }
            }
            boolean nevlasPi = hasAbility(data.faction(p.getFaction()), "PI_BOWL3_DOUBLE_VALUE")
                    && builtCount(state, e.getKey(), "PLANETARY_INSTITUTE") > 0;
            int bowl3 = p.getBowl3() * (nevlasPi ? 2 : 1) + ("BOWL3".equals(p.getBrainstone()) ? 1 : 0);
            int resources = (p.getCredits() + p.getOre() + p.getKnowledge() + p.getQic() + bowl3) / resourcesPerVp;

            Map<String, Integer> view = new LinkedHashMap<>();
            view.put("rank", rank.getOrDefault(e.getKey(), 0));
            view.put("track", track);
            view.put("resources", resources);
            view.put("bid", p.getBidVp());
            view.put("projectedTotal", p.getVp() + view.get("rank") + track + resources - p.getBidVp());
            result.put(e.getKey(), view);
        }
        return result;
    }

    private int finalMetric(GameState state, String playerId, String metric) {
        return switch (metric) {
            case "ASTEROID_BUILDINGS" -> asteroidBuildings(state, playerId);
            case "GAIA_PLANETS" -> gaiaPlanetCount(state, playerId);
            case "TOTAL_BUILDINGS" -> (int) state.getHexes().values().stream()
                    .filter(h -> hasBuildingOf(h, playerId)
                            && !"GAIAFORMER".equals(h.getBuildingType())
                            && !"SPACE_STATION".equals(h.getBuildingType()))
                    .count()
                    + (int) state.player(playerId).getArtifacts().stream()
                            .filter(a -> a.equals("ARTIFACT_7") || a.equals("ARTIFACT_8")).count(); // ⑦⑧ 가상 건물 포함 (H-3)
            case "FEDERATION_BUILDINGS" -> {
                Set<String> keys = new HashSet<>();
                for (Map<String, Object> group : state.player(playerId).getFederations()) {
                    @SuppressWarnings("unchecked")
                    List<String> buildings = (List<String>) group.get("buildings");
                    for (String key : buildings) {
                        HexState h = state.getHexes().get(key);
                        if (h != null && !"SPACE_STATION".equals(h.getBuildingType())) {
                            keys.add(key);
                        }
                    }
                }
                yield keys.size();
            }
            case "DEEP_SECTORS_WITH_BUILDING" -> deepSectorsWithBuilding(state, playerId);
            case "PLANET_TYPES" -> planetTypesWithArtifacts(state, playerId);
            case "SATELLITES" -> (int) state.getHexes().values().stream()
                    .filter(h -> h.getSatelliteOwners().contains(playerId)).count();
            case "MAX_PI_ACADEMY_DISTANCE" -> {
                List<HexCoord> pis = new ArrayList<>();
                List<HexCoord> academies = new ArrayList<>();
                for (Map.Entry<String, HexState> e : state.getHexes().entrySet()) {
                    if (!playerId.equals(e.getValue().getBuildingOwner())) {
                        continue;
                    }
                    if ("PLANETARY_INSTITUTE".equals(e.getValue().getBuildingType())) {
                        pis.add(HexCoord.parse(e.getKey()));
                    } else if ("ACADEMY".equals(e.getValue().getBuildingType())) {
                        academies.add(HexCoord.parse(e.getKey()));
                    }
                }
                int max = 0;
                for (HexCoord pi : pis) {
                    for (HexCoord academy : academies) {
                        max = Math.max(max, pi.distance(academy));
                    }
                }
                yield max;
            }
            case "BASE_SECTORS_WITH_BUILDING" -> {
                Set<String> sectors = new HashSet<>();
                for (HexState h : state.getHexes().values()) {
                    if (hasBuildingOf(h, playerId) && h.getSectorId().startsWith("SECTOR_")) {
                        sectors.add(h.getSectorId());
                    }
                }
                yield sectors.size();
            }
            default -> 0;
        };
    }

    /** 진행 플레이어 기준 시계방향(턴 순서) — 본인 제외 */
    private List<String> clockwiseFrom(GameState state, String playerId) {
        List<String> order = state.getTurnOrder();
        int idx = order.indexOf(playerId);
        List<String> result = new ArrayList<>();
        for (int step = 1; step < order.size(); step++) {
            result.add(order.get((idx + step) % order.size()));
        }
        return result;
    }

    private JsonNode faction(GameState state, String playerId) {
        return data.faction(state.player(playerId).getFaction());
    }

    private Decision requireTopDecision(GameState state, Submit submit, String expectedType) {
        Decision top = state.topDecision();
        if (top == null) {
            throw new EngineException("대기 중인 결정이 없습니다");
        }
        if (!top.getType().equals(expectedType) || !top.getId().equals(submit.decisionId())) {
            throw new EngineException("스택 최상단 결정이 아닙니다 (기대: " + top.getType() + " " + top.getId() + ")");
        }
        if (!top.getTarget().equals(submit.playerId())) {
            throw new EngineException("이 결정의 대상 플레이어가 아닙니다");
        }
        return top;
    }

    private void requireMainAction(GameState state, Submit submit) {
        if (!"PLAYING".equals(state.getPhase())) {
            throw new EngineException("PLAYING 페이즈가 아닙니다: " + state.getPhase());
        }
        if (!state.getDecisionStack().isEmpty()) {
            throw new EngineException("대기 중인 결정을 먼저 해소해야 합니다");
        }
        if (!submit.playerId().equals(state.getActivePlayer())) {
            throw new EngineException("현재 턴이 아닙니다");
        }
        if (state.isTurnEndPending()) {
            throw new EngineException("이미 메인 액션을 수행했습니다 — 자유 행동 또는 턴 종료만 가능합니다");
        }
    }

    private HexState requireHex(GameState state, Submit submit) {
        HexState hex = state.getHexes().get(coordOf(submit).key());
        if (hex == null) {
            throw new EngineException("존재하지 않는 헥스입니다");
        }
        return hex;
    }

    private HexCoord coordOf(Submit submit) {
        return new HexCoord(intOf(submit.payload(), "hexQ"), intOf(submit.payload(), "hexR"));
    }

    /**
     * VP 증감은 반드시 이 헬퍼로 — 카테고리별 분해(vpBreakdown)가 점수 팝업·종료 상세의 데이터다.
     * 카테고리: START/ROUND/PASS/TECH_TILE/TRACK/FEDERATION/FLEET/ARTIFACT/POWER_ACTION/FACTION/
     *          LEECH/INCOME/BID/FINAL_TRACK/FINAL_RESOURCES/FINAL_RANK/GAIN
     */
    private static void gainVp(PlayerState p, int amount, String category) {
        if (amount == 0) {
            return;
        }
        p.setVp(p.getVp() + amount);
        p.getVpBreakdown().merge(category, amount, Integer::sum);
    }

    /** 수입 미리보기 — 다음 수입 페이즈 예상값 (FE 플레이어 카드 위첨자용) */
    public Map<String, Map<String, Integer>> incomePreview(GameState state) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (String playerId : state.getPlayers().keySet()) {
            Income income = computeIncome(state, playerId);
            Map<String, Integer> view = new LinkedHashMap<>();
            view.put("credits", income.credits());
            view.put("ore", income.ore());
            view.put("knowledge", income.knowledge());
            view.put("qic", income.qic());
            view.put("vp", income.vp());
            view.put("powerCharge", income.charge());
            view.put("powerTokens", income.tokens());
            result.put(playerId, view);
        }
        return result;
    }

    private static int intOf(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static void requireResources(PlayerState p, int credits, int ore, int qic) {
        if (p.getCredits() < credits || p.getOre() < ore || p.getQic() < qic) {
            throw new EngineException("자원 부족 (필요: " + credits + "c " + ore + "o " + qic + "q)");
        }
    }

    private static void decreaseStock(PlayerState p, String building) {
        int current = p.stockOf(building);
        if (current < 1) {
            throw new EngineException("재고 없음: " + building);
        }
        p.getStock().put(building, current - 1);
    }

    private static boolean hasAbility(JsonNode faction, String ability) {
        for (JsonNode node : faction.get("abilities")) {
            if (ability.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode findTile(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
        }
        throw new EngineException("타일 없음: " + id);
    }

    private static Map<String, Object> resourceSnapshot(PlayerState p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("credits", p.getCredits());
        m.put("ore", p.getOre());
        m.put("knowledge", p.getKnowledge());
        m.put("qic", p.getQic());
        m.put("vp", p.getVp());
        m.put("bowl1", p.getBowl1());
        m.put("bowl2", p.getBowl2());
        m.put("bowl3", p.getBowl3());
        return m;
    }

    /** 변경된 항목만 { key: { from, to } } */
    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : before.keySet()) {
            if (!before.get(key).equals(after.get(key))) {
                changes.put(key, Map.of("from", before.get(key), "to", after.get(key)));
            }
        }
        return changes;
    }

    private static EngineEvent event(String type, Submit submit, Map<String, Object> effects,
                                     List<Map<String, Object>> pushedDecisions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", submit.payload());
        Map<String, Object> allEffects = new LinkedHashMap<>(effects);
        if (!pushedDecisions.isEmpty()) {
            allEffects.put("pushedDecisions", pushedDecisions);
        }
        payload.put("effects", allEffects);
        return new EngineEvent(type, submit.playerId(), payload);
    }
}
