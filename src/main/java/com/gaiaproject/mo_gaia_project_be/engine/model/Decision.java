package com.gaiaproject.mo_gaia_project_be.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 결정 스택의 항목. id는 상태 내 카운터 기반("d-1", "d-2" …)으로 결정성 유지 (UUID 금지 — 재생 호환). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Decision {
    private String id;
    private String type;
    private String target;
    private Map<String, Object> context;
}
