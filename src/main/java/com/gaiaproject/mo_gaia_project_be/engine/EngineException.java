package com.gaiaproject.mo_gaia_project_be.engine;

/** 룰 검증 실패 — 상태는 변경되지 않으며, 서비스 계층이 거부 응답으로 변환한다. */
public class EngineException extends RuntimeException {
    public EngineException(String message) {
        super(message);
    }
}
