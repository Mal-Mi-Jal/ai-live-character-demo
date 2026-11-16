package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 A/B/C를 같은 봇·같은 채팅(같은 시드)으로 10분 라이브를 가상 시계로 돌려 비교한다.
 * 봇 행동(누가 얼마나 자주 말하나)은 가정이다 → "공정 규칙이 실제 팬에게 이만큼 좋다"가 아니라
 * "같은 조건에서 규칙만 바꾸면 반응이 이렇게 다르게 퍼진다"를 보여 주는 실험.
 */
class FairnessTest {

    record Row(String rule, double all, double quiet, double regular, double gifters, long p50, long p95) {
    }

    static Row run(PickRule rule, long seed) {
        BotAudience audience = new BotAudience(300, seed);
        Map<String, BotAudience.Kind> kind = new HashMap<>();
        audience.bots().forEach(b -> kind.put(b.id(), b.kind()));
        LiveRoom room = new LiveRoom(rule, 3, seed);
        for (long now = 0; now < 600_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) {
                room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            }
            if (now % 3000 == 2900) room.tick(now);
        }
        int[] chat = new int[4], hit = new int[4]; // 0 전체, 1 조용한 팬, 2 단골, 3 선물한 팬
        for (FanLedger.Fan f : room.fansSnapshot()) {
            boolean got = f.reactedCount > 0;
            add(chat, hit, 0, got);
            if (kind.get(f.viewerId) == BotAudience.Kind.QUIET) add(chat, hit, 1, got);
            if (kind.get(f.viewerId) == BotAudience.Kind.REGULAR) add(chat, hit, 2, got);
            if (f.giftTotal > 0) add(chat, hit, 3, got);
        }
        LiveRoom.Stats s = room.stats();
        return new Row(rule.label(), r(hit, chat, 0), r(hit, chat, 1), r(hit, chat, 2), r(hit, chat, 3),
                s.waitP50Ms(), s.waitP95Ms());
    }

    private static void add(int[] chat, int[] hit, int i, boolean got) {
        chat[i]++;
        if (got) hit[i]++;
    }

    private static double r(int[] hit, int[] chat, int i) {
        return chat[i] == 0 ? 0 : (double) hit[i] / chat[i];
    }

    @Test
    void 공정_규칙은_반응을_더_많은_팬에게_퍼뜨리고_조용한_팬도_챙긴다() {
        Row a = run(PickRule.RANDOM, 42), b = run(PickRule.GIFT_THEN_LATEST, 42), c = run(PickRule.FAIR, 42);
        System.out.println("10분 라이브, 봇 300명, 3초마다 3명 (시드 42) — 반응 받은 팬 비율");
        System.out.println("규칙          | 전체  | 조용한 팬 | 단골  | 선물한 팬 | 대기 p50 | p95");
        for (Row x : new Row[]{a, b, c}) {
            System.out.printf("%-12s | %4.1f%% | %6.1f%%  | %4.1f%% | %6.1f%%  | %5.1f초 | %4.1f초%n",
                    x.rule(), x.all() * 100, x.quiet() * 100, x.regular() * 100, x.gifters() * 100,
                    x.p50() / 1000.0, x.p95() / 1000.0);
        }
        assertThat(c.all()).isGreaterThan(a.all()).isGreaterThan(b.all());
        assertThat(c.quiet()).isGreaterThan(a.quiet());
        // 선물한 팬이 손해 보지 않는다: 공정 규칙에서도 선물한 팬의 비율이 전체 평균 이상
        assertThat(c.gifters()).isGreaterThanOrEqualTo(c.all());
    }

    @Test
    void 공정_점수_한_번도_못_받은_팬이_이미_받은_팬보다_앞선다_선물은_상한이_있다() {
        FanLedger ledger = new FanLedger();
        Chat newbie = new Chat(1, "new", "새팬", "안녕", 0, 10_000, false);
        Chat whale = new Chat(2, "whale", "큰손", "선물", 1_000_000, 10_000, false);
        ledger.onChat(newbie);
        ledger.onChat(whale);
        ledger.onReacted(whale, 9_000); // 큰손은 이미 한 번 반응 받음
        ledger.onChat(whale);
        double n = PickRule.FairScore.of(newbie, ledger.get("new"), 12_000);
        double w = PickRule.FairScore.of(whale, ledger.get("whale"), 12_000);
        assertThat(n).isGreaterThan(w); // 100만원 선물도 보너스는 최대 30점
    }
}
