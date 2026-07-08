package com.gaiaproject.mo_gaia_project_be.engine.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDataTest {

    static GameData data;

    @BeforeAll
    static void load() {
        data = GameData.load();
    }

    @Test
    void 종족은_18종이다() {
        assertEquals(18, data.factions().size());
    }

    @Test
    void 섹터_구성() {
        assertEquals(10, data.sectors().get("baseSectors").size());
        assertEquals(19, data.sectors().get("baseLocalHexes").size());
        assertEquals(8, data.sectors().get("deepSectors").size());
        assertEquals(3, data.sectors().get("deepLocalHexes").size());
        assertEquals(10, data.sectors().get("singleHexTiles").size());
        assertEquals(10, data.sectors().get("positions").get("base").size());
        assertEquals(8, data.sectors().get("positions").get("deep").size());
        assertEquals(10, data.sectors().get("positions").get("single").size());
    }

    @Test
    void 타일_구성() {
        assertEquals(14, data.tiles().get("boosters").size());
        assertEquals(15, data.tiles().get("federationTiles").size());
        assertEquals(12, data.tiles().get("roundScoringTiles").size());
        assertEquals(9, data.tiles().get("finalScoringTiles").size());
    }

    @Test
    void 기술_구성() {
        assertEquals(6, data.tech().get("tracks").size());
        assertEquals(12, data.tech().get("basicTiles").size());
        assertEquals(21, data.tech().get("advancedTiles").size());
    }

    @Test
    void 액션_구성() {
        assertEquals(7, data.actions().get("powerActions").size());
        assertEquals(13, data.actions().get("fleetActions").size());
        assertEquals(13, data.actions().get("artifacts").size());
    }

    @Test
    void 확정값_스팟체크() {
        // start는 기본값 — 트랙 1 보상은 셋업에서 지급 (다카니안 기본 1q + 항해1 보상 → 시작 2q)
        for (var faction : data.factions()) {
            if (faction.get("id").asText().equals("DAKANIANS")) {
                assertEquals(1, faction.get("start").get("qic").asInt());
            }
            if (faction.get("id").asText().equals("MOWEIDS")) {
                assertEquals(5, faction.get("start").get("knowledge").asInt());
            }
        }
        // 가이아포밍 파워 비용 6/6/4/3/3 (레벨 1~5)
        var cost = data.tech().get("gaiaformCostByLevel");
        assertEquals(6, cost.get(1).asInt());
        assertEquals(4, cost.get(3).asInt());
        assertEquals(3, cost.get(5).asInt());
        assertTrue(data.rulesetVersion().length() > 0);
    }
}
