package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.map.MapGenerator;
import com.gaiaproject.mo_gaia_project_be.engine.model.BoardState;
import com.gaiaproject.mo_gaia_project_be.engine.model.Decision;
import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.HexState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 게임 셋업 — 맵 생성, 보드 타일 드로우, 플레이어 초기화(시작 자원 + 시작 트랙 레벨 1 보상 지급),
 * 초기 광산/의회 배치 큐 구성. 비딩 모드는 종족 배정을 경매(SETUP_BID)로 미룬다 (decision-flows §4).
 *
 * 모든 랜덤은 seed에서 파생 — 같은 seed = 같은 셋업 (이벤트 재생 결정성).
 */
public final class GameSetup {

    public record PlayerSeat(String playerId, String faction) {}

    private GameSetup() {}

    public static GameState create(GameData data, long seed, List<PlayerSeat> seats) {
        if (seats.size() != 4) {
            throw new EngineException("4인 전용 게임입니다 (현재 " + seats.size() + "명)");
        }
        Random rng = new Random(seed);
        GameState state = new GameState();
        state.setRngSeed(seed);
        state.setPhase("SETUP_MINES");
        state.setRound(0);

        generateMap(data, state, rng.nextLong());
        drawBoard(data, state.getBoard(), rng);
        initPlayers(data, state, seats);
        assignThreeShovelPlanets(data, state);
        buildSetupQueue(data, state, seats);
        pushNextSetupDecision(state);

        return state;
    }

    /** 1인 플레이 + 비딩 없음 — 4종족을 무작위로 뽑아(같은 홈행성 중복 없이) playerIds 순서대로 배정 후 바로 SETUP_MINES */
    public static GameState createLocalRandom(GameData data, long seed, List<String> playerIds) {
        if (playerIds.size() != 4) {
            throw new EngineException("4인 전용 게임입니다 (현재 " + playerIds.size() + "명)");
        }
        Random rng = new Random(seed);
        List<JsonNode> shuffled = new ArrayList<>();
        for (JsonNode faction : data.factions()) {
            shuffled.add(faction);
        }
        Collections.shuffle(shuffled, rng);
        Set<String> usedPlanets = new HashSet<>();
        List<String> pickedFactions = new ArrayList<>();
        for (JsonNode faction : shuffled) {
            if (pickedFactions.size() >= playerIds.size()) {
                break;
            }
            if (usedPlanets.add(faction.get("homePlanet").asText())) {
                pickedFactions.add(faction.get("id").asText());
            }
        }
        List<PlayerSeat> seats = new ArrayList<>();
        for (int i = 0; i < playerIds.size(); i++) {
            seats.add(new PlayerSeat(playerIds.get(i), pickedFactions.get(i)));
        }
        return create(data, seed, seats);
    }

    /** 비딩 모드 — 종족·턴 순서는 경매로 확정. playerIds = 입장 순서 (1번부터 발언). */
    public static GameState createWithBidding(GameData data, long seed, List<String> playerIds) {
        return createWithBidding(data, seed, playerIds, false);
    }

    /** turnPick=true(모드 b): 낙찰자가 종족과 함께 원하는 턴 순번을 직접 선택한다 */
    public static GameState createWithBidding(GameData data, long seed, List<String> playerIds, boolean turnPick) {
        if (playerIds.size() != 4) {
            throw new EngineException("4인 전용 게임입니다 (현재 " + playerIds.size() + "명)");
        }
        Random rng = new Random(seed);
        GameState state = new GameState();
        state.setRngSeed(seed);
        state.setPhase("SETUP_BID");
        state.setRound(0);

        generateMap(data, state, rng.nextLong());
        drawBoard(data, state.getBoard(), rng);

        BoardState board = state.getBoard();
        board.setBidTurnPick(turnPick);
        board.getBidUnassigned().addAll(playerIds);
        board.getBidActive().addAll(playerIds);
        // 비딩 후보 = 인원수만큼 랜덤 4종 — 같은 홈행성(색) 종족 2종은 동시 등장 불가
        List<JsonNode> shuffled = new ArrayList<>();
        for (JsonNode faction : data.factions()) {
            shuffled.add(faction);
        }
        Collections.shuffle(shuffled, rng);
        Set<String> usedPlanets = new HashSet<>();
        for (JsonNode faction : shuffled) {
            if (board.getFactionPool().size() >= playerIds.size()) {
                break;
            }
            if (usedPlanets.add(faction.get("homePlanet").asText())) {
                board.getFactionPool().add(faction.get("id").asText());
            }
        }
        if (!turnPick) {
            // 모드 a: 후보 순서 = 턴 슬롯 고정 (1번 후보 = 1턴 …). 낙찰자는 종족을 고르면 그 턴을 함께 가져간다
            board.getBidSlotFactions().addAll(board.getFactionPool());
        }
        state.getDecisionStack().add(new Decision(state.newDecisionId(), "BID_FACTION",
                playerIds.get(0), Map.of("currentBid", 0)));
        state.setActivePlayer(playerIds.get(0));
        return state;
    }

    /** 비딩 완료 — 확정된 턴 순서(경매 낙찰 순)로 초기 배치 페이즈 시작 */
    static void startPlacementPhase(GameData data, GameState state) {
        List<PlayerSeat> seats = new ArrayList<>();
        for (String playerId : state.getTurnOrder()) {
            seats.add(new PlayerSeat(playerId, state.player(playerId).getFaction()));
        }
        state.setPhase("SETUP_MINES");
        assignThreeShovelPlanets(data, state);
        buildSetupQueue(data, state, seats);
        pushNextSetupDecision(state);
    }

    /**
     * 모웨이드·팅커로이드 3삽 행성 배정 (공개 정보):
     * 상대 기본 종족의 모행성 전부 + 나머지 링 행성 랜덤으로 총 3개.
     * 두 종족이 함께 있으면 상대 모행성은 공유되지만 랜덤 배정분은 서로 중복되지 않는다.
     */
    static void assignThreeShovelPlanets(GameData data, GameState state) {
        List<String> ring = new ArrayList<>();
        for (JsonNode planet : data.constants().get("terraformRing")) {
            ring.add(planet.asText());
        }
        Random rng = new Random(state.getRngSeed() ^ 0x35A11L); // 셋업 시드 파생 — 재생 결정성
        Set<String> randomUsed = new HashSet<>();
        for (String playerId : state.getTurnOrder()) {
            PlayerState p = state.player(playerId);
            JsonNode faction = data.faction(p.getFaction());
            if (!hasAbility(faction, "TERRAFORM_HOME_3_OTHERS_1") || !p.getThreeShovelPlanets().isEmpty()) {
                continue;
            }
            List<String> assigned = new ArrayList<>();
            for (String otherId : state.getTurnOrder()) {
                if (otherId.equals(playerId)) {
                    continue;
                }
                String home = data.faction(state.player(otherId).getFaction()).get("homePlanet").asText();
                if (ring.contains(home) && !assigned.contains(home)) {
                    assigned.add(home);
                }
            }
            List<String> rest = new ArrayList<>();
            for (String planet : ring) {
                if (!assigned.contains(planet) && !randomUsed.contains(planet)) {
                    rest.add(planet);
                }
            }
            Collections.shuffle(rest, rng);
            while (assigned.size() < 3 && !rest.isEmpty()) {
                String pick = rest.remove(0);
                assigned.add(pick);
                randomUsed.add(pick);
            }
            p.getThreeShovelPlanets().addAll(assigned);
        }
    }

    // ── 맵 ──────────────────────────────────────

    private static void generateMap(GameData data, GameState state, long mapSeed) {
        MapGenerator.MapLayout layout = new MapGenerator(data).generate(mapSeed);
        for (Map.Entry<String, MapGenerator.HexInfo> e : layout.hexes().entrySet()) {
            MapGenerator.HexInfo h = e.getValue();
            state.getHexes().put(e.getKey(), new HexState(h.planet(), h.sectorId(), h.positionNo(), h.ship(), null, null));
        }
        for (MapGenerator.SectorPlacement p : layout.sectors()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("positionNo", p.positionNo());
            m.put("sectorId", p.sectorId());
            if (p.side() != null) {
                m.put("side", p.side());
            }
            m.put("rotation", p.rotation());
            state.getSectorPlacements().add(m);
        }
        for (MapGenerator.SingleHexPlacement p : layout.singleHexes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("positionNo", p.positionNo());
            m.put("tileId", p.tileId());
            state.getSectorPlacements().add(m);
        }
    }

    // ── 보드 타일 드로우 ──────────────────────────────────────

    private static void drawBoard(GameData data, BoardState board, Random rng) {
        board.setRoundScoringTiles(draw(ids(data.tiles().get("roundScoringTiles")), 6, rng));
        board.setFinalScoringTiles(draw(ids(data.tiles().get("finalScoringTiles")), 2, rng));

        for (String booster : draw(ids(data.tiles().get("boosters")), 7, rng)) {
            board.getBoosterHolders().put(booster, null);
        }

        // 기본 기술 타일: 9장 셔플 → 트랙 6 + COMMON 3, 확장 3장 → EXPANSION 슬롯
        List<String> basicIds = new ArrayList<>();
        List<String> expIds = new ArrayList<>();
        for (JsonNode tile : data.tech().get("basicTiles")) {
            if (tile.has("expansion")) {
                expIds.add(tile.get("id").asText());
            } else {
                basicIds.add(tile.get("id").asText());
            }
        }
        Collections.shuffle(basicIds, rng);
        Collections.shuffle(expIds, rng);
        String[] trackNames = {"TERRA_FORMING", "NAVIGATION", "AI", "GAIA_FORMING", "ECONOMY", "SCIENCE"};
        for (int i = 0; i < 6; i++) {
            board.getTechOffers().put(trackNames[i], basicIds.get(i));
        }
        for (int i = 0; i < 3; i++) {
            board.getTechOffers().put("COMMON_" + (i + 1), basicIds.get(6 + i));
        }
        for (int i = 0; i < 3; i++) {
            board.getTechOffers().put("EXPANSION_" + (i + 1), expIds.get(i));
        }
        // 확장 기술 타일은 함대 3척(TF 마스·이클립스·리벨리온)에 1장씩 연결 — 트와일라잇은 기술 타일 없음.
        // 타일 배정 랜덤성은 expIds 셔플에서 나온다. 그 함대 입장자만 획득 가능.
        List<String> techShips = List.of("TF_MARS", "ECLIPSE", "REBELLION");
        for (int i = 0; i < 3; i++) {
            board.getExpansionTechShips().put("EXPANSION_" + (i + 1), techShips.get(i));
        }

        // 고급 타일 7장: 트랙 6 + COMMON
        List<String> advDraw = draw(ids(data.tech().get("advancedTiles")), 7, rng);
        for (int i = 0; i < 6; i++) {
            board.getAdvTechOffers().put(trackNames[i], advDraw.get(i));
        }
        board.getAdvTechOffers().put("COMMON", advDraw.get(6));

        // 연방 타일: 기본 6종 중 1종 → 테라포밍 트랙(1) + 공급 2, 나머지 각 3. 확장 8종 중 4종 → 함대 1~4
        List<String> baseFed = new ArrayList<>();
        List<String> expFed = new ArrayList<>();
        for (JsonNode tile : data.tiles().get("federationTiles")) {
            if (tile.has("gleensOnly")) {
                continue;
            }
            if (tile.has("expansion")) {
                expFed.add(tile.get("id").asText());
            } else {
                baseFed.add(tile.get("id").asText());
            }
        }
        Collections.shuffle(baseFed, rng);
        board.setTerraformTrackFedTile(baseFed.get(0));
        board.getFederationSupply().put(baseFed.get(0), 2);
        for (int i = 1; i < baseFed.size(); i++) {
            board.getFederationSupply().put(baseFed.get(i), 3);
        }
        Collections.shuffle(expFed, rng);
        List<String> ships = List.of("TF_MARS", "ECLIPSE", "REBELLION", "TWILIGHT");
        for (int i = 0; i < 4; i++) {
            board.getFleetFedTiles().put(ships.get(i), expFed.get(i));
        }

        board.setEconomyOption(rng.nextBoolean() ? "A" : "B");
        board.setCommonAdvCondition(rng.nextBoolean() ? "VP_25" : "FLEET_3");

        // 인공물: 13종 중 4개 랜덤 (선착순 획득)
        for (String artifact : draw(ids(data.actions().get("artifacts")), 4, rng)) {
            board.getArtifactOffers().put(artifact, null);
        }
    }

    private static List<String> ids(JsonNode array) {
        List<String> list = new ArrayList<>();
        for (JsonNode node : array) {
            list.add(node.get("id").asText());
        }
        return list;
    }

    private static List<String> draw(List<String> pool, int count, Random rng) {
        List<String> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rng);
        return new ArrayList<>(copy.subList(0, count));
    }

    // ── 플레이어 초기화 ──────────────────────────────────────

    private static void initPlayers(GameData data, GameState state, List<PlayerSeat> seats) {
        for (PlayerSeat seat : seats) {
            initPlayer(data, state, seat.playerId(), seat.faction());
            state.getTurnOrder().add(seat.playerId());
        }
    }

    /** 플레이어 한 명 초기화 — 시작 자원·재고·트랙 보상. 턴 순서 등록은 호출부 책임. */
    static void initPlayer(GameData data, GameState state, String playerId, String factionId) {
        JsonNode faction = data.faction(factionId);
        JsonNode start = faction.get("start");

        PlayerState p = new PlayerState();
        p.setFaction(factionId);
        p.setVp(start.get("vp").asInt());
        p.getVpBreakdown().put("START", start.get("vp").asInt());
        p.setCredits(start.get("credits").asInt());
        p.setOre(start.get("ore").asInt());
        p.setKnowledge(start.get("knowledge").asInt());
        p.setQic(start.get("qic").asInt());
        p.setBowl1(start.get("bowl1").asInt());
        p.setBowl2(start.get("bowl2").asInt());
        p.setBowl3(start.get("bowl3").asInt());
        if (start.has("brainstone")) {
            p.setBrainstone(start.get("brainstone").asText());
        }
        p.getStock().put("MINE", 8);
        p.getStock().put("TRADING_STATION", 4);
        p.getStock().put("RESEARCH_LAB", 3);
        p.getStock().put("PLANETARY_INSTITUTE", 1);
        p.getStock().put("ACADEMY", 2);
        p.getStock().put("GAIAFORMER", 0);

        applyStartTracks(data, faction, p);

        // 모웨이드: T.F 마스 함대 무료 자동 입장 (✅확정)
        JsonNode startFleet = faction.path("setup").path("startFleet");
        if (!startFleet.isMissingNode()) {
            p.getFleetProbes().add(startFleet.asText());
        }

        state.getPlayers().put(playerId, p);
    }

    /** 시작 트랙 설정 + 레벨 1 보상 지급 (✅확정: 트랙 1 보상은 게임 시작 시 지급) */
    private static void applyStartTracks(GameData data, JsonNode faction, PlayerState p) {
        JsonNode startTracks = faction.get("startTracks");
        if (startTracks == null) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : startTracks.properties()) {
            String trackKey = e.getKey().toUpperCase();
            String track = switch (trackKey) {
                case "TERRAFORMING" -> "TERRA_FORMING";
                case "GAIAFORMING" -> "GAIA_FORMING";
                default -> trackKey;
            };
            int level = e.getValue().asInt();
            p.getTracks().put(track, level);
            JsonNode levels = data.tech().get("tracks").get(track).get("levels");
            if (levels == null) {
                continue; // 경제·지식은 수입 트랙 — 레벨 1 즉시 보상 없음
            }
            for (int lv = 1; lv <= level; lv++) {
                applyTrackGain(faction, p, levels.get(lv - 1));
            }
        }
    }

    private static void applyTrackGain(JsonNode faction, PlayerState p, JsonNode reward) {
        if (reward == null || !reward.has("gain")) {
            return;
        }
        JsonNode gain = reward.get("gain");
        p.setOre(p.getOre() + gain.path("ore").asInt(0));
        p.setKnowledge(p.getKnowledge() + gain.path("knowledge").asInt(0));
        p.setCredits(p.getCredits() + gain.path("credits").asInt(0));
        p.setBowl1(p.getBowl1() + gain.path("powerTokens").asInt(0));
        int qicGain = gain.path("qic").asInt(0);
        if (qicGain > 0) {
            if (hasAbility(faction, "QIC_TO_ORE_UNTIL_QIC_ACADEMY")) {
                p.setOre(p.getOre() + qicGain);
            } else {
                p.setQic(p.getQic() + qicGain);
            }
        }
        int gaiaformer = gain.path("gaiaformer").asInt(0);
        if (gaiaformer > 0) {
            p.getStock().merge("GAIAFORMER", gaiaformer, Integer::sum);
        }
    }

    private static boolean hasAbility(JsonNode faction, String ability) {
        for (JsonNode node : faction.get("abilities")) {
            if (ability.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    // ── 초기 배치 큐: 기본종족 스네이크(1→4→4→1) → 제노스 3번째 → 확장종족 좌석순 → 하이브(placeLast) ──

    private static void buildSetupQueue(GameData data, GameState state, List<PlayerSeat> seats) {
        List<Map<String, String>> queue = state.getBoard().getSetupQueue();

        List<PlayerSeat> basics = new ArrayList<>();
        List<PlayerSeat> expansions = new ArrayList<>();
        List<PlayerSeat> lasts = new ArrayList<>();
        for (PlayerSeat seat : seats) {
            JsonNode faction = data.faction(seat.faction());
            if (faction.path("setup").path("placeLast").asBoolean(false)) {
                lasts.add(seat);
            } else if (faction.path("expansion").asBoolean(false)) {
                expansions.add(seat);
            } else {
                basics.add(seat);
            }
        }
        // 1) 기본 종족 스네이크: 좌석 순 1번째 광산 → 역순 2번째 광산
        for (PlayerSeat seat : basics) {
            queue.add(Map.of("player", seat.playerId(), "building", "MINE"));
        }
        for (int i = basics.size() - 1; i >= 0; i--) {
            queue.add(Map.of("player", basics.get(i).playerId(), "building", "MINE"));
        }
        // 2) 제노스 3번째 광산
        for (PlayerSeat seat : basics) {
            if (data.faction(seat.faction()).path("setup").path("initialMines").asInt(2) >= 3) {
                queue.add(Map.of("player", seat.playerId(), "building", "MINE"));
            }
        }
        // 3) 확장 종족: 좌석 순 1회씩 (팅커로이드는 의회, 그 외 광산 1개)
        for (PlayerSeat seat : expansions) {
            boolean pi = data.faction(seat.faction()).path("setup").path("placePiInsteadOfMines").asBoolean(false);
            queue.add(Map.of("player", seat.playerId(), "building", pi ? "PLANETARY_INSTITUTE" : "MINE"));
        }
        // 4) 하이브(placeLast): 항상 마지막에 의회
        for (PlayerSeat seat : lasts) {
            queue.add(Map.of("player", seat.playerId(), "building", "PLANETARY_INSTITUTE"));
        }
    }

    static void pushNextSetupDecision(GameState state) {
        List<Map<String, String>> queue = state.getBoard().getSetupQueue();
        if (queue.isEmpty()) {
            startBoosterPhase(state);
            return;
        }
        Map<String, String> head = queue.get(0);
        state.getDecisionStack().add(new Decision(
                state.newDecisionId(), "PLACE_INITIAL_MINE", head.get("player"),
                Map.of("building", head.get("building"))));
        state.setActivePlayer(head.get("player"));
    }

    /** 초기 배치 완료 → 부스터 역순 선택 (좌석 4→1). 스택 LIFO이므로 1→4 순으로 push. */
    private static void startBoosterPhase(GameState state) {
        state.setPhase("SETUP_BOOSTER");
        for (String playerId : state.getTurnOrder()) {
            state.getDecisionStack().add(new Decision(
                    state.newDecisionId(), "CHOOSE_BOOSTER", playerId, Map.of()));
        }
        state.setActivePlayer(state.topDecision().getTarget());
    }
}
