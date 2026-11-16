package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다음 라이브 복귀율. 라이브 1(10분) → 라이브 2(10분), 음성 데모 설정(목소리 1명 + 화면 ❤ 3명, 5초 창, 4차례마다 묶음).
 *
 * 중요: 봇에게는 마음이 없어서 "인정 받으면 다시 온다"는 효과를 이 데모가 증명할 수 없다.
 * 그래서 복귀 확률을 두 부분으로 나눠 직접 넣는다 (둘 다 가정):
 *   기본 복귀 확률 (유형별: 단골 85%, 큰손 80%, 보통 55%, 조용한 팬 35%)
 * + 인정 효과 boost (지난 라이브에서 인정 받았으면 +boost)
 * boost = 0 이면 "효과가 전혀 없어도 숫자가 어떻게 보이나", boost = 0.10 이면 "효과가 있다면 규칙에 따라 얼마나 달라지나".
 */
class ReturnRateTest {

    record Row(String rule, double boost, double ackedRate, double notAckedRate, double overall, int prev) {
    }

    static double base(BotAudience.Kind k) {
        return switch (k) {
            case REGULAR -> 0.85;
            case WHALE -> 0.80;
            case NORMAL -> 0.55;
            case QUIET -> 0.35;
        };
    }

    static void live(LiveRoom room, BotAudience audience, java.util.function.Predicate<BotAudience.Bot> present, long start) {
        long next = start + 3000;
        int turns = 0;
        for (long now = start; now < start + 600_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100, present)) {
                room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            }
            if (now < next) continue;
            String line = null;
            if ((turns + 1) % 4 == 0) {
                LiveRoom.GroupMoment g = room.groupMoment(now);
                if (g != null) line = g.line();
            }
            if (line == null) {
                LiveRoom.TickResult r = room.tick(now);
                if (r.reactions().isEmpty()) {
                    next = now + 1000;
                    continue;
                }
                line = r.reactions().get(0).line();
            }
            turns++;
            next = now + Math.round(line.length() / VoiceModeTest.CHARS_PER_SEC * 1000) + 400;
        }
    }

    static Row run(PickRule rule, int bots, double boost, long seed) {
        BotAudience audience = new BotAudience(bots, seed);
        LiveRoom room = new LiveRoom(rule, 1, 3, 5_000, seed);
        live(room, audience, b -> true, 0);

        // 라이브 1에서 인정 받은 팬
        Set<String> acked = new HashSet<>();
        for (FanLedger.Fan f : room.fansSnapshot()) {
            if (f.reactedCount + f.screenAcked + f.groupAcked > 0) acked.add(f.viewerId);
        }
        room.startNextLive();

        // 라이브 2에 올지 정한다 (규칙과 무관한 같은 난수열 → 규칙끼리 공정 비교)
        Random attend = new Random(seed * 7 + 3);
        Set<String> present = new HashSet<>();
        for (BotAudience.Bot b : audience.bots()) {
            double p = base(b.kind()) + (acked.contains(b.id()) ? boost : 0);
            if (attend.nextDouble() < p) present.add(b.id());
        }
        live(room, audience, b -> present.contains(b.id()), 3_600_000);

        LiveRoom.ReturnStats rs = room.returnStats();
        int prev = rs.prevAcked() + rs.prevNotAcked();
        return new Row(rule.label(), boost, rs.ackedRate(), rs.notAckedRate(),
                (double) (rs.returnedAcked() + rs.returnedNotAcked()) / prev, prev);
    }

    @Test
    void 다음_라이브_복귀율_효과가_없어도_인정_받은_팬이_더_돌아온_것처럼_보인다() {
        System.out.println("다음 라이브 복귀율 (봇 1000명, 라이브 1·2 각 10분, 음성 데모 설정, 시드 42) — 복귀 확률은 가정");
        System.out.println("인정 효과(가정) | 규칙        | 인정 받은 팬 복귀 | 못 받은 팬 복귀 | 전체 복귀");
        Row a0 = null, c0 = null, b1 = null, c1 = null;
        for (double boost : new double[]{0, 0.10}) {
            for (PickRule rule : PickRule.all()) {
                Row r = run(rule, 1000, boost, 42);
                if (boost == 0 && rule == PickRule.RANDOM) a0 = r;
                if (boost == 0 && rule == PickRule.FAIR) c0 = r;
                if (boost > 0 && rule == PickRule.GIFT_THEN_LATEST) b1 = r;
                if (boost > 0 && rule == PickRule.FAIR) c1 = r;
                System.out.printf("  +%2.0f%%p       | %-10s | %5.1f%% | %5.1f%% | %5.1f%% (지난 라이브 %d명)%n",
                        boost * 100, r.rule(), r.ackedRate() * 100, r.notAckedRate() * 100, r.overall() * 100, r.prev());
            }
        }
        // 효과 0인데도 무작위 규칙에서 "인정 받은 팬"이 더 많이 돌아온다: 자주 말하는 팬(단골)이 인정도 잘 받고 원래 잘 돌아오기 때문
        assertThat(a0.ackedRate()).isGreaterThan(a0.notAckedRate());
        // 효과가 있다면, 더 많은 팬을 인정하는 공정 규칙에서 전체 복귀가 더 높다
        assertThat(c1.overall()).isGreaterThan(b1.overall());
    }

    @Test
    void 다음_라이브에서_기억은_남고_숫자는_새로_센다_오프닝은_지난_라이브_요약() {
        LiveRoom room = new LiveRoom(PickRule.FAIR, 1, 3, 5_000, 1);
        for (int i = 0; i < 5; i++) room.receive("f" + i, "팬" + i, "오늘 라면 먹었어", 0, 1_000, false);
        room.tick(2_000);
        LiveRoom.LiveSummary s = room.startNextLive();
        assertThat(s.topLabel()).isEqualTo("라면");
        assertThat(s.openingLine()).contains("라면").contains("5명");
        assertThat(room.stats().chatters()).isZero();
        assertThat(room.stats().liveNo()).isEqualTo(2);

        // 라이브 2: 지난번에 온 팬이 다시 오면 알아본다
        room.receive("f3", "팬3", "ㅋㅋ", 0, 3_600_000, false); // 잡담 → 다시 온 것을 알아본다
        LiveRoom.Reaction r = room.tick(3_601_000).reactions().get(0);
        assertThat(r.recall()).isNotNull(); // 라면 기억 또는 "지난 라이브"
        assertThat(room.returnStats().prevAcked() + room.returnStats().prevNotAcked()).isEqualTo(5);
    }
}
