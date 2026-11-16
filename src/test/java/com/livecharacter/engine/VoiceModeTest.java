package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 음성으로 답하면 몇 명에게 닿나. 캐릭터 입은 하나라서 한 번에 한 명, 앞 대사가 끝나야 다음 대사.
 *
 * 실측값 (2026-11-16):
 *  - Haiku 대사 54줄 평균 65자 (생각 단계 켠 기본값, 90자 제한)
 *  - Windows 내장 한국어 음성(Heami)으로 읽으면 초당 5.7자 → 한 줄 평균 11.4초
 * 가정: 다음 대사는 앞 대사를 읽는 동안 미리 만든다 (LLM 시간이 읽는 시간보다 짧으면 처리량은 읽는 속도가 정한다)
 */
class VoiceModeTest {

    static final double CHARS_PER_SEC = 5.7;

    record Row(String label, double reach, double quiet, long p50, long p95, int spoken) {
    }

    /** lineChars 길이 대사를 한 명씩 읽는 라이브 10분 */
    static Row voice(PickRule rule, int bots, int lineChars, long seed) {
        BotAudience audience = new BotAudience(bots, seed);
        java.util.Map<String, BotAudience.Kind> kind = new java.util.HashMap<>();
        audience.bots().forEach(b -> kind.put(b.id(), b.kind()));
        LiveRoom room = new LiveRoom(rule, 1, seed);
        long slotMs = Math.round(lineChars / CHARS_PER_SEC * 1000);
        long nextPick = slotMs;
        int spoken = 0;
        for (long now = 0; now < 600_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) {
                room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            }
            if (now >= nextPick) {
                spoken += room.tick(now).reactions().size();
                nextPick = now + slotMs;
            }
        }
        int chat = 0, hit = 0, qChat = 0, qHit = 0;
        for (FanLedger.Fan f : room.fansSnapshot()) {
            chat++;
            if (f.reactedCount > 0) hit++;
            if (kind.get(f.viewerId) == BotAudience.Kind.QUIET) {
                qChat++;
                if (f.reactedCount > 0) qHit++;
            }
        }
        LiveRoom.Stats s = room.stats();
        // 고른 순간부터 실제로 목소리가 나오기까지 앞 대사 하나를 더 기다린다 (미리 만들어 두는 구조)
        return new Row(rule.label(), (double) hit / chat, qChat == 0 ? 0 : (double) qHit / qChat,
                s.waitP50Ms() + slotMs, s.waitP95Ms() + slotMs, spoken);
    }

    record Full(String label, double voice, double any, double quiet, double quietAny, long p50, long p95, int turns, int groups) {
    }

    /**
     * 지금 데모 설정: 한 차례에 목소리 1명(35자 안팎 템플릿 대사) + 화면 ❤ 3명, 4차례마다 묶음 인정(3명 이상일 때).
     * 대사 길이는 실제 템플릿 대사 길이로 계산한다.
     */
    static Full voiceDemo(PickRule rule, int bots, long seed) {
        BotAudience audience = new BotAudience(bots, seed);
        java.util.Map<String, BotAudience.Kind> kind = new java.util.HashMap<>();
        audience.bots().forEach(b -> kind.put(b.id(), b.kind()));
        LiveRoom room = new LiveRoom(rule, 1, 3, seed);
        long next = 3000;
        int turns = 0, groups = 0;
        java.util.List<Long> starts = new java.util.ArrayList<>();
        for (long now = 0; now < 600_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) {
                room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            }
            if (now < next) continue;
            String line = null;
            if ((turns + 1) % 4 == 0) {
                LiveRoom.GroupMoment g = room.groupMoment(now);
                if (g != null) {
                    line = g.line();
                    groups++;
                }
            }
            if (line == null) {
                LiveRoom.TickResult r = room.tick(now);
                if (r.reactions().isEmpty()) {
                    next = now + 1000;
                    continue;
                }
                line = r.reactions().get(0).line();
                starts.add(r.reactions().get(0).waitMs());
            }
            turns++;
            next = now + Math.round(line.length() / CHARS_PER_SEC * 1000) + 400;
        }
        int chat = 0, voice = 0, any = 0, qChat = 0, qVoice = 0, qAny = 0;
        for (FanLedger.Fan f : room.fansSnapshot()) {
            boolean v = f.reactedCount > 0, a = v || f.screenAcked > 0 || f.groupAcked > 0;
            chat++;
            if (v) voice++;
            if (a) any++;
            if (kind.get(f.viewerId) == BotAudience.Kind.QUIET) {
                qChat++;
                if (v) qVoice++;
                if (a) qAny++;
            }
        }
        java.util.Collections.sort(starts);
        long p50 = starts.isEmpty() ? 0 : starts.get(starts.size() / 2);
        long p95 = starts.isEmpty() ? 0 : starts.get((int) Math.ceil(0.95 * starts.size()) - 1);
        return new Full(rule.label(), (double) voice / chat, (double) any / chat,
                qChat == 0 ? 0 : (double) qVoice / qChat, qChat == 0 ? 0 : (double) qAny / qChat, p50, p95, turns, groups);
    }

    @Test
    void 지금_데모_설정_목소리_1명_화면_3명_묶음_인정() {
        System.out.println("음성 데모 설정 10분 (목소리 1명 + 화면 ❤ 3명, 4차례마다 묶음, 템플릿 대사 길이, 시드 42)");
        System.out.println("봇   | 규칙        | 차례(묶음) | 목소리로 불린 팬 | 하나라도 인정 | 조용한 팬 목소리/인정 | 고르기까지 대기 p50 | p95 (+대사 생성 시간)");
        Full c300 = null, a300 = null;
        for (int bots : new int[]{300, 1000}) {
            for (PickRule rule : PickRule.all()) {
                Full f = voiceDemo(rule, bots, 42);
                if (bots == 300 && rule == PickRule.FAIR) c300 = f;
                if (bots == 300 && rule == PickRule.RANDOM) a300 = f;
                System.out.printf("%4d | %-10s | %3d(%2d) | %5.1f%% | %5.1f%% | %5.1f%% / %5.1f%% | %5.1f초 | %5.1f초%n",
                        bots, f.label(), f.turns(), f.groups(), f.voice() * 100, f.any() * 100,
                        f.quiet() * 100, f.quietAny() * 100, f.p50() / 1000.0, f.p95() / 1000.0);
            }
        }
        assertThat(c300.any()).isGreaterThan(c300.voice()); // 화면·묶음 인정이 목소리보다 넓게 닿는다
        assertThat(c300.quietAny()).isGreaterThan(a300.quietAny());
    }

    @Test
    void 음성이면_한_사람씩이라_닿는_팬이_크게_줄고_공정_규칙이_더_중요해진다() {
        System.out.println("음성 모드 10분 라이브 (한 번에 한 명, 초당 5.7자로 읽음, 시드 42)");
        System.out.println("봇   | 대사 길이 | 규칙        | 말 건 횟수 | 닿은 팬 | 조용한 팬 | 채팅→목소리 p50 | p95");
        for (int bots : new int[]{300, 1000}) {
            for (int len : new int[]{65, 30}) {
                for (PickRule rule : PickRule.all()) {
                    Row r = voice(rule, bots, len, 42);
                    System.out.printf("%4d | %3d자(%4.1f초) | %-10s | %4d번 | %5.1f%% | %6.1f%% | %5.1f초 | %5.1f초%n",
                            bots, len, len / CHARS_PER_SEC, r.label(), r.spoken(), r.reach() * 100, r.quiet() * 100,
                            r.p50() / 1000.0, r.p95() / 1000.0);
                }
            }
        }
        Row fair65 = voice(PickRule.FAIR, 300, 65, 42), fair30 = voice(PickRule.FAIR, 300, 30, 42);
        Row rnd30 = voice(PickRule.RANDOM, 300, 30, 42);
        assertThat(fair30.reach()).isGreaterThan(fair65.reach()); // 짧게 말하면 더 많은 팬에게 닿는다
        assertThat(fair30.quiet()).isGreaterThan(rnd30.quiet());
    }
}
