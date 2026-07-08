package com.gaiaproject.mo_gaia_project_be.engine.map;

/**
 * Axial 좌표 (q, r), flat-top 헥스.
 * 회전: cube 변환 후 시계방향 60도/스텝 (x,y,z) → (-z,-x,-y).
 */
public record HexCoord(int q, int r) {

    public static HexCoord parse(String key) {
        int i = key.indexOf(',');
        return new HexCoord(Integer.parseInt(key.substring(0, i)), Integer.parseInt(key.substring(i + 1)));
    }

    public String key() {
        return q + "," + r;
    }

    public HexCoord add(HexCoord other) {
        return new HexCoord(q + other.q, r + other.r);
    }

    /** steps × 60도 시계방향 회전 */
    public HexCoord rotateCw(int steps) {
        int x = q, z = r, y = -x - z;
        int n = ((steps % 6) + 6) % 6;
        for (int i = 0; i < n; i++) {
            int nx = -z, ny = -x, nz = -y;
            x = nx;
            y = ny;
            z = nz;
        }
        return new HexCoord(x, z);
    }

    public int distance(HexCoord other) {
        int dq = q - other.q;
        int dr = r - other.r;
        int ds = (-q - r) - (-other.q - other.r);
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(ds)) / 2;
    }
}
