package com.gaiaproject.mo_gaia_project_be.engine;

import java.util.Map;

/**
 * 엔진이 적용한 변경 하나 — 서비스 계층이 game_event 행으로 저장한다.
 * payload에는 input과 효과 요약(자원 from→to, vpLog, pushedDecisions)이 담긴다.
 */
public record EngineEvent(String type, String actor, Map<String, Object> payload) {}
