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
    void 함대_타일은_서로_인접하지_않는다() {
        for (long seed = 0; seed < 30; seed++) {
            MapGenerator.MapLayout layout = new MapGenerator(data).generate(seed);

            Set<Integer> fleetPositions = new HashSet<>();
            for (MapGenerator.SingleHexPlacement p : layout.singleHexes()) {
                if (p.tileId().startsWith("FLEET_")) {
                    fleetPositions.add(p.positionNo());
                }
            }
            assertEquals(4, fleetPositions.size());

            for (var pair : data.sectors().get("fleetAdjacency")) {
                boolean bothFleet = fleetPositions.contains(pair.get(0).asInt())
                        && fleetPositions.contains(pair.get(1).asInt());
                assertFalse(bothFleet, "seed " + seed + ": 함대 인접 위반 " + pair);
            }
        }
    }

    @Test
    void 시드마다_다른_맵이_나온다() {
        MapGenerator gen = new MapGenerator(data);
        assertFalse(gen.generate(1L).sectors().equals(gen.generate(2L).sectors()));
    }
}
