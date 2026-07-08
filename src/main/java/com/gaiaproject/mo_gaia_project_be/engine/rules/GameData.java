package com.gaiaproject.mo_gaia_project_be.engine.rules;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 정적 게임 데이터 단일 소스 — src/main/resources/gamedata/*.json 로더.
 * 종족·타일·섹터 데이터는 DB가 아닌 이 리소스가 진실이다 (game.ruleset_version으로 버전 기록).
 */
public final class GameData {

    private final JsonNode sectors;
    private final JsonNode factions;
    private final JsonNode tech;
    private final JsonNode tiles;
    private final JsonNode actions;
    private final JsonNode constants;

    private GameData(JsonNode sectors, JsonNode factions, JsonNode tech,
                     JsonNode tiles, JsonNode actions, JsonNode constants) {
        this.sectors = sectors;
        this.factions = factions;
        this.tech = tech;
        this.tiles = tiles;
        this.actions = actions;
        this.constants = constants;
    }

    public static GameData load() {
        JsonMapper mapper = JsonMapper.builder().build();
        return new GameData(
                read(mapper, "sectors"),
                read(mapper, "factions"),
                read(mapper, "tech"),
                read(mapper, "tiles"),
                read(mapper, "actions"),
                read(mapper, "constants"));
    }

    private static JsonNode read(JsonMapper mapper, String name) {
        String path = "/gamedata/" + name + ".json";
        try (InputStream in = GameData.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("게임 데이터 리소스 없음: " + path);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("게임 데이터 로드 실패: " + path, e);
        }
    }

    public JsonNode sectors() {
        return sectors;
    }

    public JsonNode factions() {
        return factions.get("factions");
    }

    public JsonNode faction(String id) {
        for (JsonNode node : factions()) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
        }
        throw new IllegalArgumentException("종족 없음: " + id);
    }

    public JsonNode tech() {
        return tech;
    }

    public JsonNode tiles() {
        return tiles;
    }

    public JsonNode actions() {
        return actions;
    }

    public JsonNode constants() {
        return constants;
    }

    public String rulesetVersion() {
        return constants.get("rulesetVersion").asText();
    }
}
