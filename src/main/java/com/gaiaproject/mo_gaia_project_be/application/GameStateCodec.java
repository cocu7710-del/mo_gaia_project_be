package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** GameState ↔ JSONB 직렬화 — 스냅샷 저장·복원의 유일한 경로 */
@Component
public class GameStateCodec {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(GameState state) {
        return mapper.writeValueAsString(state);
    }

    public GameState read(String json) {
        return mapper.readValue(json, GameState.class);
    }

    public String writeMap(Map<String, Object> map) {
        return mapper.writeValueAsString(map);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        return mapper.readValue(json, Map.class);
    }
}
