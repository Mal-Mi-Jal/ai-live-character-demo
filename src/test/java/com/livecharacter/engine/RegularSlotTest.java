package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단골은 목소리로 몇 번 불리나 (2026-11-17, 지원 뒤 스스로 찾은 약점).
 *
 * 공정 점수에서 처음 온 팬은 +60, 목소리로 한 번 불린 팬은 −40 → 한 번 불린 단골의 최고 점수는 20(기다림 상한 60 − 40)이라
 * 처음 온 팬(60 이상)이 한 명이라도 있으면 못 이긴다. 그래서 "기존 팬 몫"(목소리 차례 N번에 한 번은 아는 팬 중에서)을
 * 설정으로 추가하고, 기본값 0(끔)과 비교한다. 0이면 VoiceModeTest와 같은 숫자가 나와야 한다 (README·이력서 숫자 유지).
 *
 * 설정은 VoiceModeTest.voiceDemo와 같다: 10분, 목소리 1명 + 화면 ❤ 3명, 4차례마다 묶음, 템플릿 대사 길이, 목소리 창 5초.
 */
class RegularSlotTest {

    /** 유형별: 채팅한 팬 수, 목소리로 1번 이상, 2번 이상 불린 팬 수, 목소리 호명 합계 */
    record KindStat(int chat, int voice1, int voice2, int calls) {
        double rate1() { return chat == 0 ? 0 : (double) voice1 / chat; }
        double rate2() { return chat == 0 ? 0 : (double) voice2 / chat; }
        double perFan() { return chat == 0 ? 0 : (double) calls / chat; }
    }

    record Result(int every, int turns, double voice, double any, Map<BotAudience.Kind, KindStat> kinds) {
    }

    static Result run(PickRule rule, int bots, int every, long seed) {
        BotAudience audience = new BotAudience(bots, seed);
        Map<String, BotAudience.Kind> kind = new HashMap<>();
        audience.bots().forEach(b -> kind.put(b.id(), b.kind()));
        LiveRoom room = new LiveRoom(rule, 1, 3, 5_000, seed);
        room.setRegularEvery(every);
        long next = 3000;
        int turns = 0;
        for (long now = 0; now < 600_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) {
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
        Map<BotAudience.Kind, int[]> acc = new EnumMap<>(BotAudience.Kind.class);
        int chat = 0, voice = 0, any = 0;
        for (FanLedger.Fan f : room.fansSnapshot()) {
            chat++;
            if (f.reactedCount > 0) voice++;
            if (f.reactedCount > 0 || f.screenAcked > 0 || f.groupAcked > 0) any++;
            int[] a = acc.computeIfAbsent(kind.get(f.viewerId), k -> new int[4]);
            a[0]++;
            if (f.reactedCount >= 1) a[1]++;
            if (f.reactedCount >= 2) a[2]++;
            a[3] += f.reactedCount;
        }
        Map<BotAudience.Kind, KindStat> kinds = new EnumMap<>(BotAudience.Kind.class);
        acc.forEach((k, a) -> kinds.put(k, new KindStat(a[0], a[1], a[2], a[3])));
        return new Result(every, turns, (double) voice / chat, (double) any / chat, kinds);
    }

    static String pct(double v) {
        return String.format("%5.1f%%", v * 100);
    }

    @Test
    void 기존_팬_몫을_켜면_단골이_다시_불리고_처음_온_팬은_조금_줄어든다() {
        System.out.println("기존 팬 몫 비교 (10분, 목소리 창 5초, C 공정, 시드 42). 몫 0 = 지금 기본값");
        System.out.println("봇   | 몫     | 목소리 팬 | 하나라도 인정 | 단골 1번↑ / 2번↑ / 1인당 | 보통 1번↑ | 조용한 팬 1번↑ | 큰손 1번↑");
        Map<String, Result> all = new HashMap<>();
        for (int bots : new int[]{300, 1000}) {
            for (int every : new int[]{0, 5, 3}) {
                Result r = run(PickRule.FAIR, bots, every, 42);
                all.put(bots + "/" + every, r);
                KindStat reg = r.kinds().get(BotAudience.Kind.REGULAR);
                System.out.printf("%4d | %-6s | %s | %s | %s / %s / %.2f번 | %s | %s | %s%n",
                        bots, every == 0 ? "끔" : every + "번에1", pct(r.voice()), pct(r.any()),
                        pct(reg.rate1()), pct(reg.rate2()), reg.perFan(),
                        pct(r.kinds().get(BotAudience.Kind.NORMAL).rate1()),
                        pct(r.kinds().get(BotAudience.Kind.QUIET).rate1()),
                        pct(r.kinds().get(BotAudience.Kind.WHALE).rate1()));
            }
        }

        // 몫 0(끔)은 지금 기본 동작과 같아야 한다 → README·이력서 숫자가 그대로 재현된다
        Result off = all.get("300/0");
        VoiceModeTest.Full base = VoiceModeTest.voiceDemo(PickRule.FAIR, 300, 5_000, 42);
        assertThat(off.voice()).isEqualTo(base.voice());
        assertThat(off.any()).isEqualTo(base.any());
        assertThat(off.kinds().get(BotAudience.Kind.QUIET).rate1()).isEqualTo(base.quiet());

        // 몫을 켜면 단골이 두 번 이상 불리는 비율이 오른다
        Result on = all.get("300/5");
        assertThat(on.kinds().get(BotAudience.Kind.REGULAR).rate2())
                .isGreaterThan(off.kinds().get(BotAudience.Kind.REGULAR).rate2());
    }
}
