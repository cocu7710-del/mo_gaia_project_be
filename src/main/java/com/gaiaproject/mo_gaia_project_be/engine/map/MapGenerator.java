package com.gaiaproject.mo_gaia_project_be.engine.map;

import tools.jackson.databind.JsonNode;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 맵 생성기 (docs/data/map.md 알고리즘, 4인 전용).
 * 1. 기본 섹터: SECTOR_1~4 중 2개 → 중앙(5,6), 나머지 8개 셔플 → {1,2,3,4,7,8,9,10}, 회전 랜덤
 * 2. 충돌 해소: 같은 행성 타입(차원 변형 제외)이 타 섹터와 거리 ≤1이면 60°씩 회전 (최대 6회)
 * 3. 딥 섹터: 8개 전부, FRONT/BACK 랜덤, 셔플 → 11~18 (회전 고정)
 * 4. 1헥스 타일: 함대 4척은 인접 그래프 배제 배치, 나머지 6개 랜덤
 * 5. 글로벌 헥스 생성 (겹침 시 예외)
 *
 * seed가 같으면 항상 같은 맵 — 결과는 MAP_GENERATED 이벤트 payload로 기록되어 재생 시 재추첨하지 않는다.
 */
public class MapGenerator {

    public record SectorPlacement(int positionNo, String sectorId, String side, int rotation) {}

    public record SingleHexPlacement(int positionNo, String tileId) {}

    public record HexInfo(String planet, String sectorId, int positionNo, String ship) {}

    public record MapLayout(List<SectorPlacement> sectors,
                            List<SingleHexPlacement> singleHexes,
                            Map<String, HexInfo> hexes) {}

    private static final String TRANSDIM = "TRANSDIM";
    private static final String EMPTY = "EMPTY";
    private static final int[] CENTER_POSITIONS = {5, 6};
    private static final int[] OUTER_POSITIONS = {1, 2, 3, 4, 7, 8, 9, 10};

    private final GameData data;

    public MapGenerator(GameData data) {
        this.data = data;
    }

    public MapLayout generate(long seed) {
        Random rng = new Random(seed);
        JsonNode sectorsJson = data.sectors();

        List<SectorPlacement> basePlacements = placeBaseSectors(rng, sectorsJson);
        resolveConflicts(basePlacements, sectorsJson);
        List<SectorPlacement> deepPlacements = placeDeepSectors(rng, sectorsJson);
        List<SingleHexPlacement> singlePlacements = placeSingleHexTiles(rng, sectorsJson);

        List<SectorPlacement> allSectors = new ArrayList<>(basePlacements);
        allSectors.addAll(deepPlacements);

        Map<String, HexInfo> hexes = generateGlobalHexes(sectorsJson, allSectors, singlePlacements);
        return new MapLayout(allSectors, singlePlacements, hexes);
    }

    // ── 1. 기본 섹터 배치 ──────────────────────────────────────

    private List<SectorPlacement> placeBaseSectors(Random rng, JsonNode sectorsJson) {
        List<String> centerCandidates = new ArrayList<>(List.of("SECTOR_1", "SECTOR_2", "SECTOR_3", "SECTOR_4"));
        Collections.shuffle(centerCandidates, rng);

        List<String> rest = new ArrayList<>();
        rest.add(centerCandidates.get(2));
        rest.add(centerCandidates.get(3));
        for (int i = 5; i <= 10; i++) {
            rest.add("SECTOR_" + i);
        }
        Collections.shuffle(rest, rng);

        List<SectorPlacement> placements = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            placements.add(new SectorPlacement(CENTER_POSITIONS[i], centerCandidates.get(i), null, rng.nextInt(6) * 60));
        }
        for (int i = 0; i < OUTER_POSITIONS.length; i++) {
            placements.add(new SectorPlacement(OUTER_POSITIONS[i], rest.get(i), null, rng.nextInt(6) * 60));
        }
        placements.sort((a, b) -> Integer.compare(a.positionNo(), b.positionNo()));
        return placements;
    }

    // ── 2. 행성 충돌 해소 ──────────────────────────────────────

    private void resolveConflicts(List<SectorPlacement> placements, JsonNode sectorsJson) {
        for (int idx = 0; idx < placements.size(); idx++) {
            for (int attempt = 0; attempt < 6; attempt++) {
                if (!hasConflict(placements, idx, sectorsJson)) {
                    break;
                }
                SectorPlacement p = placements.get(idx);
                placements.set(idx, new SectorPlacement(p.positionNo(), p.sectorId(), p.side(), (p.rotation() + 60) % 360));
            }
        }
    }

    private boolean hasConflict(List<SectorPlacement> placements, int idx, JsonNode sectorsJson) {
        SectorPlacement target = placements.get(idx);
        Map<HexCoord, String> targetPlanets = globalPlanets(target, sectorsJson);

        for (int j = 0; j < placements.size(); j++) {
            if (j == idx) {
                continue;
            }
            Map<HexCoord, String> otherPlanets = globalPlanets(placements.get(j), sectorsJson);
            for (Map.Entry<HexCoord, String> mine : targetPlanets.entrySet()) {
                if (TRANSDIM.equals(mine.getValue())) {
                    continue;
                }
                for (Map.Entry<HexCoord, String> theirs : otherPlanets.entrySet()) {
                    if (mine.getValue().equals(theirs.getValue()) && mine.getKey().distance(theirs.getKey()) <= 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 기본 섹터의 EMPTY 아닌 행성만 글로벌 좌표로 */
    private Map<HexCoord, String> globalPlanets(SectorPlacement placement, JsonNode sectorsJson) {
        JsonNode sector = findById(sectorsJson.get("baseSectors"), placement.sectorId());
        HexCoord offset = basePositionOffset(sectorsJson, placement.positionNo());
        Map<HexCoord, String> result = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : sector.get("planets").properties()) {
            HexCoord global = HexCoord.parse(entry.getKey()).rotateCw(placement.rotation() / 60).add(offset);
            result.put(global, entry.getValue().asText());
        }
        return result;
    }

    // ── 3. 딥 섹터 배치 ──────────────────────────────────────

    private List<SectorPlacement> placeDeepSectors(Random rng, JsonNode sectorsJson) {
        List<String> deepIds = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            deepIds.add("DEEP_SECTOR_" + i);
        }
        Collections.shuffle(deepIds, rng);

        List<SectorPlacement> placements = new ArrayList<>();
        JsonNode deepPositions = sectorsJson.get("positions").get("deep");
        for (int i = 0; i < deepIds.size(); i++) {
            JsonNode pos = deepPositions.get(i);
            String side = rng.nextBoolean() ? "front" : "back";
            placements.add(new SectorPlacement(pos.get("positionNo").asInt(), deepIds.get(i), side, pos.get("rotation").asInt()));
        }
        return placements;
    }

    // ── 4. 1헥스 타일 배치 (함대 인접 배제) ──────────────────────

    private List<SingleHexPlacement> placeSingleHexTiles(Random rng, JsonNode sectorsJson) {
        List<String> fleetIds = new ArrayList<>();
        List<String> otherIds = new ArrayList<>();
        for (JsonNode tile : sectorsJson.get("singleHexTiles")) {
            if (tile.has("ship")) {
                fleetIds.add(tile.get("id").asText());
            } else {
                otherIds.add(tile.get("id").asText());
            }
        }

        Set<String> adjacency = new HashSet<>();
        for (JsonNode pair : sectorsJson.get("fleetAdjacency")) {
            adjacency.add(pair.get(0).asInt() + "-" + pair.get(1).asInt());
            adjacency.add(pair.get(1).asInt() + "-" + pair.get(0).asInt());
        }

        List<Integer> positions = new ArrayList<>();
        for (JsonNode pos : sectorsJson.get("positions").get("single")) {
            positions.add(pos.get("positionNo").asInt());
        }

        for (int attempt = 0; attempt < 200; attempt++) {
            List<Integer> shuffled = new ArrayList<>(positions);
            Collections.shuffle(shuffled, rng);
            List<Integer> fleetPositions = shuffled.subList(0, fleetIds.size());
            if (fleetPositionsValid(fleetPositions, adjacency)) {
                Collections.shuffle(fleetIds, rng);
                Collections.shuffle(otherIds, rng);
                List<SingleHexPlacement> placements = new ArrayList<>();
                for (int i = 0; i < fleetIds.size(); i++) {
                    placements.add(new SingleHexPlacement(fleetPositions.get(i), fleetIds.get(i)));
                }
                List<Integer> restPositions = shuffled.subList(fleetIds.size(), shuffled.size());
                for (int i = 0; i < otherIds.size(); i++) {
                    placements.add(new SingleHexPlacement(restPositions.get(i), otherIds.get(i)));
                }
                placements.sort((a, b) -> Integer.compare(a.positionNo(), b.positionNo()));
                return placements;
            }
        }
        throw new IllegalStateException("함대 타일 배치 실패 (인접 제약을 만족하는 배치를 찾지 못함)");
    }

    private boolean fleetPositionsValid(List<Integer> fleetPositions, Set<String> adjacency) {
        for (int i = 0; i < fleetPositions.size(); i++) {
            for (int j = i + 1; j < fleetPositions.size(); j++) {
                if (adjacency.contains(fleetPositions.get(i) + "-" + fleetPositions.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ── 5. 글로벌 헥스 생성 ──────────────────────────────────────

    private Map<String, HexInfo> generateGlobalHexes(JsonNode sectorsJson,
                                                     List<SectorPlacement> sectors,
                                                     List<SingleHexPlacement> singles) {
        Map<String, HexInfo> hexes = new LinkedHashMap<>();

        for (SectorPlacement placement : sectors) {
            boolean isDeep = placement.sectorId().startsWith("DEEP_");
            JsonNode localHexes = sectorsJson.get(isDeep ? "deepLocalHexes" : "baseLocalHexes");
            JsonNode sector = findById(sectorsJson.get(isDeep ? "deepSectors" : "baseSectors"), placement.sectorId());
            JsonNode planets = isDeep ? sector.get(placement.side()) : sector.get("planets");
            HexCoord offset = isDeep
                    ? positionOffset(sectorsJson, "deep", placement.positionNo())
                    : basePositionOffset(sectorsJson, placement.positionNo());

            for (JsonNode localKey : localHexes) {
                String key = localKey.asText();
                String planet = planets.has(key) ? planets.get(key).asText() : EMPTY;
                HexCoord global = HexCoord.parse(key).rotateCw(placement.rotation() / 60).add(offset);
                putHex(hexes, global, new HexInfo(planet, placement.sectorId(), placement.positionNo(), null));
            }
        }

        for (SingleHexPlacement placement : singles) {
            JsonNode tile = findById(sectorsJson.get("singleHexTiles"), placement.tileId());
            HexCoord offset = positionOffset(sectorsJson, "single", placement.positionNo());
            String ship = tile.has("ship") ? tile.get("ship").asText() : null;
            putHex(hexes, offset, new HexInfo(tile.get("planet").asText(), placement.tileId(), placement.positionNo(), ship));
        }
        return hexes;
    }

    private void putHex(Map<String, HexInfo> hexes, HexCoord coord, HexInfo info) {
        HexInfo previous = hexes.put(coord.key(), info);
        if (previous != null) {
            throw new IllegalStateException("헥스 겹침: " + coord.key() + " (" + previous.sectorId() + " vs " + info.sectorId() + ")");
        }
    }

    // ── 헬퍼 ──────────────────────────────────────

    private HexCoord basePositionOffset(JsonNode sectorsJson, int positionNo) {
        return positionOffset(sectorsJson, "base", positionNo);
    }

    private HexCoord positionOffset(JsonNode sectorsJson, String kind, int positionNo) {
        for (JsonNode pos : sectorsJson.get("positions").get(kind)) {
            if (pos.get("positionNo").asInt() == positionNo) {
                return new HexCoord(pos.get("q").asInt(), pos.get("r").asInt());
            }
        }
        throw new IllegalArgumentException("포지션 없음: " + kind + " " + positionNo);
    }

    private JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
        }
        throw new IllegalArgumentException("id 없음: " + id);
    }
}
