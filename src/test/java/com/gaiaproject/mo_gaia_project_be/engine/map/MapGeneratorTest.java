package com.gaiaproject.mo_gaia_project_be.engine.map;

import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapGeneratorTest {

    static GameData data;

    @BeforeAll
    static void load() {
        data = GameData.load();
    }

    @Test
    void 같은_시드는_같은_맵을_만든다() {
        MapGenerator gen = new MapGenerator(data);
        MapGenerator.MapLayout a = gen.generate(42L);
        MapGenerator.MapLayout b = gen.generate(42L);
        assertEquals(a.sectors(), b.sectors());
        assertEquals(a.singleHexes(), b.singleHexes());
        assertEquals(a.hexes(), b.hexes());
    }

    @Test
    void 배치_구성이_완전하다() {
        MapGenerator.MapLayout layout = new MapGenerator(data).generate(7L);

        assertEquals(18, layout.sectors().size());       // 기본 10 + 딥 8
        assertEquals(10, layout.singleHexes().size());   // 1헥스 타일 10

        // 포지션 중복 없음
        Set<Integer> positions = new HashSet<>();
        layout.sectors().forEach(s -> assertTrue(positions.add(s.positionNo())));
        layout.singleHexes().forEach(s -> assertTrue(positions.add(s.positionNo())));

        // 헥스 총수 = 10×19 + 8×3 + 10 = 224, 겹침 없음(생성기가 겹침 시 예외)
        assertEquals(224, layout.hexes().size());
    }

    @Test
    void 함대_타일은_서로_3칸_이내에_배치되지_않는다() {
        java.util.Map<Integer, HexCoord> singleCoords = new java.util.HashMap<>();
        for (var pos : data.sectors().get("positions").get("single")) {
            singleCoords.put(pos.get("positionNo").asInt(),
                    new HexCoord(pos.get("q").asInt(), pos.get("r").asInt()));
        }
        for (long seed = 0; seed < 30; seed++) {
            MapGenerator.MapLayout layout = new MapGenerator(data).generate(seed);

            java.util.List<HexCoord> fleets = new java.util.ArrayList<>();
            for (MapGenerator.SingleHexPlacement p : layout.singleHexes()) {
                if (p.tileId().startsWith("FLEET_")) {
                    fleets.add(singleCoords.get(p.positionNo()));
                }
            }
            assertEquals(4, fleets.size());

            for (int i = 0; i < fleets.size(); i++) {
                for (int j = i + 1; j < fleets.size(); j++) {
                    assertTrue(fleets.get(i).distance(fleets.get(j)) >= 4,
                            "seed " + seed + ": 함대 거리 " + fleets.get(i).distance(fleets.get(j)));
                }
            }
        }
    }

    @Test
    void 중앙_2칸은_섹터_1_4_중에서_랜덤_배치된다() {
        Set<String> centerCandidates = Set.of("SECTOR_1", "SECTOR_2", "SECTOR_3", "SECTOR_4");
        for (long seed = 0; seed < 30; seed++) {
            MapGenerator.MapLayout layout = new MapGenerator(data).generate(seed);
            for (MapGenerator.SectorPlacement s : layout.sectors()) {
                if (s.positionNo() == 5 || s.positionNo() == 6) {
                    assertTrue(centerCandidates.contains(s.sectorId()),
                            "seed " + seed + ": 중앙에 " + s.sectorId());
                }
            }
        }
    }

    @Test
    void 시드마다_다른_맵이_나온다() {
        MapGenerator gen = new MapGenerator(data);
        assertFalse(gen.generate(1L).sectors().equals(gen.generate(2L).sectors()));
    }
}
