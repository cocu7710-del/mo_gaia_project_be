package com.gaiaproject.mo_gaia_project_be.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class HexState {

    public HexState(String planet, String sectorId, int positionNo, String ship,
                    String buildingOwner, String buildingType) {
        this.planet = planet;
        this.sectorId = sectorId;
        this.positionNo = positionNo;
        this.ship = ship;
        this.buildingOwner = buildingOwner;
        this.buildingType = buildingType;
    }

    private String planet;
    private String sectorId;
    private int positionNo;
    /** 잊혀진 함대 우주선 (TF_MARS/REBELLION/ECLIPSE/TWILIGHT), 없으면 null */
    private String ship;
    private String buildingOwner;
    private String buildingType;
    /** 아카데미 전용: KNOWLEDGE / QIC */
    private String academyType;
    /** 연방 위성 소유자들 — 한 헥스에 서로 다른 플레이어의 위성 공존 가능 (EMPTY 헥스, 우주정거장 위 포함) */
    private List<String> satelliteOwners = new ArrayList<>();
    /** 란티다 기생 광산 소유자 — 상대 건물과 공존 (파워값 1 고정) */
    private String parasiteOwner;
    /** 모웨이드 링 부착 여부 (파워값 +2, 건물당 1회) */
    private boolean ring;

    public boolean hasBuilding() {
        return buildingType != null;
    }
}
