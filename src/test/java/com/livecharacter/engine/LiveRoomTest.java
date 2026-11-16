package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LiveRoomTest {

    @Test
    void 도배해도_한_사람은_후보_한_줄이고_반응은_k명까지() {
        LiveRoom room = new LiveRoom(PickRule.RANDOM, 3, 1);
        for (int i = 0; i < 50; i++) room.receive("spam", "도배", "ㅋㅋ" + i, 0, 1000, false);
        room.receive("a", "에이", "안녕", 0, 1000, false);
        room.receive("b", "비", "안녕", 0, 1000, false);
        room.receive("c", "씨", "안녕", 0, 1000, false);

        LiveRoom.TickResult r = room.tick(4000);

        assertThat(r.stats().lastBatchSize()).isEqualTo(53);
        assertThat(r.stats().lastCandidates()).isEqualTo(4);
        assertThat(r.reactions()).hasSize(3);
        Set<String> ids = new HashSet<>();
        r.reactions().forEach(x -> ids.add(x.viewerId()));
        assertThat(ids).hasSize(3); // 같은 사람 두 번 없음
    }

    @Test
    void 선물_규칙은_한_틱_안의_선물을_합쳐서_본다() {
        LiveRoom room = new LiveRoom(PickRule.GIFT_THEN_LATEST, 1, 1);
        room.receive("whale", "큰손", "선물!", 5000, 1000, false);
        room.receive("whale", "큰손", "그냥 채팅", 0, 1100, false); // 선물 뒤에 일반 채팅
        room.receive("late", "늦게", "안녕", 1000, 1200, false);

        LiveRoom.TickResult r = room.tick(4000);

        assertThat(r.reactions()).singleElement().satisfies(x -> {
            assertThat(x.viewerId()).isEqualTo("whale");
            assertThat(x.gift()).isEqualTo(5000);
            assertThat(x.chatText()).isEqualTo("그냥 채팅");
            assertThat(x.waitMs()).isEqualTo(2900); // 고른 채팅(1100ms) 기준
        });
    }

    @Test
    void 개인정보는_가리고_금칙어_채팅은_후보에서_뺀다() {
        LiveRoom room = new LiveRoom(PickRule.RANDOM, 3, 1);
        Chat phone = room.receive("a", "에이", "내 번호 010-1234-5678 이야", 0, 0, false);
        room.receive("b", "비", "솔라 바보", 0, 0, false);

        assertThat(phone.text()).doesNotContain("1234").contains("(번호 가림)");
        LiveRoom.TickResult r = room.tick(3000);
        assertThat(r.reactions()).extracting(LiveRoom.Reaction::viewerId).containsExactly("a");
        assertThat(r.stats().blockedChats()).isEqualTo(1);
        assertThat(r.stats().chatters()).isEqualTo(1); // 금칙어만 쓴 사람은 "채팅한 팬"에 안 센다
    }

    @Test
    void 같은_시드면_같은_반응이_나온다() {
        assertThat(simulate(42)).isEqualTo(simulate(42));
        assertThat(simulate(42)).isNotEqualTo(simulate(43));
    }

    /** 봇 300명, 2분 라이브를 가상 시계로 돌려 반응 받은 사람 목록을 돌려준다 */
    private static List<String> simulate(long seed) {
        BotAudience audience = new BotAudience(300, seed);
        LiveRoom room = new LiveRoom(PickRule.RANDOM, 3, seed);
        List<String> picked = new ArrayList<>();
        for (long now = 0; now < 120_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) {
                room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            }
            if (now % 3000 == 2900) room.tick(now).reactions().forEach(x -> picked.add(x.viewerId()));
        }
        return picked;
    }

    @Test
    void 선물순은_큰손이_반응을_독점한다_무작위보다_반응받은_팬이_적다() {
        double random = ratio(PickRule.RANDOM);
        double gift = ratio(PickRule.GIFT_THEN_LATEST);
        System.out.printf("2분 라이브, 봇 300명: 무작위 %.1f%% / 선물·최신순 %.1f%%%n", random * 100, gift * 100);
        assertThat(gift).isLessThan(random);
    }

    private static double ratio(PickRule rule) {
        BotAudience audience = new BotAudience(300, 7);
        LiveRoom room = new LiveRoom(rule, 3, 7);
        for (long now = 0; now < 120_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) {
                room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            }
            if (now % 3000 == 2900) room.tick(now);
        }
        return room.stats().reactedRatio();
    }

    @Test
    void 여러_스레드가_동시에_보내도_채팅이_사라지지_않는다() throws Exception {
        LiveRoom room = new LiveRoom(PickRule.RANDOM, 3, 1);
        int threads = 8, each = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> seenInTicks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int tt = t;
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < each; i++) room.receive("t" + tt + "-" + i, "n", "안녕", 0, i, false);
                return null;
            });
        }
        // 보내는 도중에 틱과 채팅 꺼내기도 같이 돈다
        pool.submit(() -> {
            go.await();
            for (int i = 0; i < 50; i++) {
                synchronized (seenInTicks) { seenInTicks.add(room.tick(i).stats().lastBatchSize()); }
                room.takeUnflushed();
            }
            return null;
        });
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        int lastBatch = room.tick(999).stats().lastBatchSize();
        int sum = seenInTicks.stream().mapToInt(Integer::intValue).sum() + lastBatch;
        assertThat(room.stats().totalChats()).isEqualTo((long) threads * each);
        assertThat(sum).isEqualTo(threads * each); // 모든 채팅이 정확히 한 번씩 어떤 틱에 들어갔다
    }

    @Test
    void 규칙을_바꾸면_숫자를_처음부터_센다() {
        LiveRoom room = new LiveRoom(PickRule.RANDOM, 3, 1);
        room.receive("a", "에이", "안녕", 0, 0, false);
        room.tick(3000);
        room.reset(PickRule.GIFT_THEN_LATEST);
        LiveRoom.Stats s = room.stats();
        assertThat(s.rule()).isEqualTo("gift");
        assertThat(s.chatters()).isZero();
        assertThat(s.totalChats()).isZero();
    }
}
