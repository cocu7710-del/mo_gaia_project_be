package com.gaiaproject.mo_gaia_project_be.engine.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HexCoordTest {

    @Test
    void 거리_계산() {
        assertEquals(0, new HexCoord(0, 0).distance(new HexCoord(0, 0)));
        assertEquals(1, new HexCoord(0, 0).distance(new HexCoord(1, 0)));
        assertEquals(1, new HexCoord(0, 0).distance(new HexCoord(1, -1)));
        assertEquals(2, new HexCoord(0, 0).distance(new HexCoord(2, -1)));
        assertEquals(4, new HexCoord(-2, 0).distance(new HexCoord(2, 0)));
    }

    @Test
    void 회전_6번이면_원위치() {
        HexCoord c = new HexCoord(2, -1);
        assertEquals(c, c.rotateCw(6));
        assertEquals(c, c.rotateCw(1).rotateCw(5));
    }

    @Test
    void 회전해도_원점과의_거리는_유지() {
        HexCoord origin = new HexCoord(0, 0);
        HexCoord c = new HexCoord(2, -1);
        for (int steps = 0; steps < 6; steps++) {
            assertEquals(c.distance(origin), c.rotateCw(steps).distance(origin));
        }
    }

    @Test
    void 파싱과_키_왕복() {
        assertEquals(new HexCoord(-2, 3), HexCoord.parse("-2,3"));
        assertEquals("-2,3", new HexCoord(-2, 3).key());
    }
}
