package com.gaiaproject.mo_gaia_project_be.api;

import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정적 게임 데이터 카탈로그 — FE가 종족 목록·이름 등을 하드코딩하지 않도록 제공 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final GameData data;

    public MetaController(GameData data) {
        this.data = data;
    }

    @GetMapping(value = "/factions", produces = MediaType.APPLICATION_JSON_VALUE)
    public String factions() {
        return data.factions().toString();
    }

    /** 부스터·라운드/최종 점수·연방 타일 정의 */
    @GetMapping(value = "/tiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public String tiles() {
        return data.tiles().toString();
    }

    /** 연구 트랙·기본/고급 기술 타일 정의 */
    @GetMapping(value = "/tech", produces = MediaType.APPLICATION_JSON_VALUE)
    public String tech() {
        return data.tech().toString();
    }

    /** 파워/함대 액션·인공물 정의 */
    @GetMapping(value = "/actions", produces = MediaType.APPLICATION_JSON_VALUE)
    public String actions() {
        return data.actions().toString();
    }
}
