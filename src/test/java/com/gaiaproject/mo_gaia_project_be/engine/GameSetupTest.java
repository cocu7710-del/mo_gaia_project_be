package com.gaiaproject.mo_gaia_project_be.engine;

import com.gaiaproject.mo_gaia_project_be.engine.model.GameState;
import com.gaiaproject.mo_gaia_project_be.engine.model.PlayerState;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSetupTest {

    static GameData data;

    @BeforeAll
    static void load() {
        data = GameData.load();
    }

    private static List<GameSetup.PlayerSeat> seats() {
        return List.of(
                new GameSetup.PlayerSeat("p1", "GEODENS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "XENOS"),
                new GameSetup.PlayerSeat("p4", "TAKLONS"));
    }

    @Test
    void 시작_트랙_레벨1_보상이_셋업에서_지급된다() {
        GameState state = GameSetup.create(data, 11L, seats());

        PlayerState geodens = state.player("p1");
        assertEquals(6, geodens.getOre());                    // 4 + 테라포밍1 보상 2
        assertEquals(1, geodens.track("TERRA_FORMING"));

        PlayerState gleens = state.player("p2");
        assertEquals(5, gleens.getOre());                     // 4 + 항해1 QIC 보상 → 광석 변환
        assertEquals(0, gleens.getQic());

        PlayerState xenos = state.player("p3");
        assertEquals(2, xenos.getQic());                      // 1 + AI1 보상 1

        assertEquals("BOWL1", state.player("p4").getBrainstone());
    }

    @Test
    void 다카니안은_기본1_항해보상1로_QIC_2로_시작한다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "DAKANIANS"),
                new GameSetup.PlayerSeat("p2", "GLEENS"),
                new GameSetup.PlayerSeat("p3", "XENOS"),
                new GameSetup.PlayerSeat("p4", "TAKLONS")));
        assertEquals(2, state.player("p1").getQic());         // 기본 1 + 항해1 보상 1 (경제1은 수입 트랙)
        assertEquals(1, state.player("p1").track("ECONOMY"));
    }

    @Test
    void 보드_드로우_구성() {
        GameState state = GameSetup.create(data, 11L, seats());
        assertEquals(6, state.getBoard().getRoundScoringTiles().size());
        assertEquals(2, state.getBoard().getFinalScoringTiles().size());
        assertEquals(7, state.getBoard().getBoosterHolders().size());
        assertEquals(12, state.getBoard().getTechOffers().size());   // 트랙 6 + COMMON 3 + EXPANSION 3
        assertEquals(7, state.getBoard().getAdvTechOffers().size()); // 트랙 6 + COMMON
        assertEquals(6, state.getBoard().getFederationSupply().size());
        assertEquals(4, state.getBoard().getFleetFedTiles().size());
        assertNotNull(state.getBoard().getEconomyOption());
    }

    @Test
    void 초기_배치_큐는_스네이크_순서다() {
        GameState state = GameSetup.create(data, 11L, seats());
        var queue = state.getBoard().getSetupQueue();
        // 1차 p1~p4 광산 + 2차 역순 p4~p1 광산 + 제노스 3번째 = 9
        assertEquals(9, queue.size());
        assertEquals("p1", queue.get(0).get("player"));
        assertEquals("p4", queue.get(3).get("player"));
        assertEquals("p4", queue.get(4).get("player"));      // 역순 시작
        assertEquals("p1", queue.get(7).get("player"));
        assertEquals("p3", queue.get(8).get("player"));      // 제노스 추가 광산

        assertEquals("SETUP_MINES", state.getPhase());
        assertEquals("PLACE_INITIAL_MINE", state.topDecision().getType());
        assertEquals("p1", state.topDecision().getTarget());
    }

    @Test
    void 초기_배치는_기본종족_제노스_확장종족_하이브_순서다() {
        // 다카니안(확장)이 1번 좌석이어도 기본 종족 스네이크가 먼저다
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "DAKANIANS"),   // 확장
                new GameSetup.PlayerSeat("p2", "GLEENS"),      // 기본
                new GameSetup.PlayerSeat("p3", "XENOS"),       // 기본 (광산 3)
                new GameSetup.PlayerSeat("p4", "IVITS")));     // placeLast 의회
        var queue = state.getBoard().getSetupQueue();
        // 기본 스네이크 p2,p3,p3,p2 → 제노스 3번째 p3 → 확장 p1 → 하이브 p4 = 7
        assertEquals(7, queue.size());
        assertEquals("p2", queue.get(0).get("player"));
        assertEquals("p3", queue.get(1).get("player"));
        assertEquals("p3", queue.get(2).get("player"));      // 역순 시작
        assertEquals("p2", queue.get(3).get("player"));
        assertEquals("p3", queue.get(4).get("player"));      // 제노스 3번째 광산
        assertEquals("p1", queue.get(5).get("player"));      // 확장 종족
        assertEquals("MINE", queue.get(5).get("building"));
        assertEquals("p4", queue.get(6).get("player"));      // 하이브 마지막 의회
        assertEquals("PLANETARY_INSTITUTE", queue.get(6).get("building"));

        assertEquals("p2", state.topDecision().getTarget()); // 1턴 다카니안이 아니라 첫 기본 종족부터
    }

    @Test
    void 팅커로이드는_확장_그룹_차례에_의회를_놓는다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "TINKEROIDS"),  // 확장 + placePiInsteadOfMines
                new GameSetup.PlayerSeat("p2", "GEODENS"),
                new GameSetup.PlayerSeat("p3", "GLEENS"),
                new GameSetup.PlayerSeat("p4", "TAKLONS")));
        var queue = state.getBoard().getSetupQueue();
        // 기본 스네이크 p2,p3,p4,p4,p3,p2 → 팅커로이드 의회 = 7
        assertEquals(7, queue.size());
        assertEquals("p2", queue.get(0).get("player"));
        assertEquals("p1", queue.get(6).get("player"));
        assertEquals("PLANETARY_INSTITUTE", queue.get(6).get("building"));
    }

    @Test
    void 모웨이드와_팅커로이드는_3삽_행성이_중복_없이_배정된다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "MOWEIDS"),      // 초월 차원 시작
                new GameSetup.PlayerSeat("p2", "TINKEROIDS"),   // 소행성 시작
                new GameSetup.PlayerSeat("p3", "GEODENS"),      // OXIDE
                new GameSetup.PlayerSeat("p4", "GLEENS")));     // DESERT
        var mo = state.player("p1").getThreeShovelPlanets();
        var tink = state.player("p2").getThreeShovelPlanets();

        // 상대 기본 종족 모행성 2개 + 랜덤 1개 = 3개
        assertEquals(3, mo.size());
        assertEquals(3, tink.size());
        assertTrue(mo.containsAll(List.of("OXIDE", "DESERT")));
        assertTrue(tink.containsAll(List.of("OXIDE", "DESERT")));

        // 랜덤 배정분은 서로 중복 금지
        String moRandom = mo.stream().filter(x -> !x.equals("OXIDE") && !x.equals("DESERT")).findFirst().orElseThrow();
        String tinkRandom = tink.stream().filter(x -> !x.equals("OXIDE") && !x.equals("DESERT")).findFirst().orElseThrow();
        assertNotEquals(moRandom, tinkRandom);

        // 기본 종족·고정 삽 확장 종족(스자·다카)은 배정 없음
        assertTrue(state.player("p3").getThreeShovelPlanets().isEmpty());
    }

    @Test
    void 상대가_전부_기본_종족이면_3삽_행성은_모행성_3개로_랜덤_없이_확정된다() {
        GameState state = GameSetup.create(data, 11L, List.of(
                new GameSetup.PlayerSeat("p1", "MOWEIDS"),
                new GameSetup.PlayerSeat("p2", "GEODENS"),      // OXIDE
                new GameSetup.PlayerSeat("p3", "GLEENS"),       // DESERT
                new GameSetup.PlayerSeat("p4", "NEVLAS")));     // ICE
        var mo = state.player("p1").getThreeShovelPlanets();
        assertEquals(3, mo.size());
        assertTrue(mo.containsAll(List.of("OXIDE", "DESERT", "ICE"))); // 랜덤 보충 없음
    }

    @Test
    void 같은_시드는_같은_셋업이다() {
        GameState a = GameSetup.create(data, 99L, seats());
        GameState b = GameSetup.create(data, 99L, seats());
        assertEquals(a, b);
    }
}
