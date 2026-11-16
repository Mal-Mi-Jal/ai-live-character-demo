package com.livecharacter.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DonationQueueTest {

    @Test
    void 들어온_순서대로_하나씩_읽고_짧게_감사한다() {
        DonationQueue q = new DonationQueue();
        q.offer("a", "에이", 1000, "솔라 화이팅", false, 0);
        q.offer("b", "비", 10000, "", false, 100);

        DonationQueue.Plan p1 = q.next(1_000, false);
        assertThat(p1.kind()).isEqualTo(DonationQueue.Kind.READ);
        assertThat(p1.readText()).isEqualTo("에이님 1,000원 후원. 솔라 화이팅");
        assertThat(p1.thanks()).startsWith("에이님 1,000원 선물 감사합니다~ "); // + 메시지에 짧은 답

        DonationQueue.Plan p2 = q.next(2_000, false);
        assertThat(p2.readText()).isEqualTo("비님 10,000원 후원"); // 메시지 없으면 알림만
        assertThat(p2.thanks()).contains("비님 10,000원").endsWith("꼬리별이 막 흔들려요!"); // 큰 후원은 한 마디 더
        assertThat(q.next(3_000, false).kind()).isEqualTo(DonationQueue.Kind.NONE);
    }

    @Test
    void 번갈아_읽느라_이번_차례를_건너뛰어도_후원이_사라지지_않는다() {
        // 버그 재현(2026-11-17): 꺼낸 다음에 "앞 차례도 후원이니 이번엔 안 읽기"로 정하면 꺼낸 후원이 사라졌다
        DonationQueue q = new DonationQueue();
        q.offer("a", "에이", 1000, "첫째", false, 0);
        q.offer("b", "비", 1000, "둘째", false, 0);
        assertThat(q.next(1_000, false, true).items().get(0).nickname()).isEqualTo("에이");
        assertThat(q.next(2_000, false, false).kind()).isEqualTo(DonationQueue.Kind.NONE); // 일반 채팅 차례
        assertThat(q.snapshot()).extracting(DonationQueue.Donation::nickname).containsExactly("비");
        assertThat(q.next(3_000, false, true).items().get(0).nickname()).isEqualTo("비");
    }

    @Test
    void 후원_메시지에_주제에_맞게_짧게_답한다() {
        assertThat(DonationQueue.reply("솔라 오늘 방송 재밌어요!", 0)).contains("꼬리별");
        assertThat(DonationQueue.reply("저녁 라면 먹었어요", 0)).startsWith("라면");
        assertThat(DonationQueue.reply("야근해서 피곤해", 0)).contains("쉬어요");
        assertThat(DonationQueue.reply(null, 0)).isNull(); // 메시지 없으면 감사만
    }

    @Test
    void 떡밥_진행_중이면_보류하고_끝나면_읽는다() {
        DonationQueue q = new DonationQueue();
        q.offer("a", "에이", 1000, "질문 중에 후원", false, 0);
        assertThat(q.next(5_000, true).kind()).isEqualTo(DonationQueue.Kind.NONE);
        assertThat(q.snapshot()).hasSize(1);
        assertThat(q.next(25_000, false).kind()).isEqualTo(DonationQueue.Kind.READ);
    }

    @Test
    void 쌓이거나_오래_기다리면_메시지는_건너뛰고_묶어서_감사() {
        DonationQueue q = new DonationQueue();
        for (int i = 0; i < DonationQueue.BATCH_AT; i++) q.offer("v" + i, "팬" + i, 1000, "메시지" + i, false, i);
        DonationQueue.Plan p = q.next(10_000, true); // 진행 중이어도 쌓이면 짧게 묶어서
        assertThat(p.kind()).isEqualTo(DonationQueue.Kind.BATCH);
        assertThat(p.readText()).isNull();
        assertThat(p.thanks()).isEqualTo("팬0님, 팬1님 외 4분 후원 감사합니다!");
        assertThat(q.snapshot()).isEmpty();

        q.offer("x", "늦은팬", 5000, "안녕", false, 0);
        assertThat(q.next(DonationQueue.MAX_WAIT_MS + 1, false).kind()).isEqualTo(DonationQueue.Kind.BATCH);
    }

    @Test
    void 메시지의_개인정보는_가리고_금칙어면_메시지는_안_읽는다() {
        DonationQueue q = new DonationQueue();
        q.offer("a", "에이", 1000, "내 번호 010-1234-5678", false, 0);
        q.offer("b", "비", 1000, "솔라 바보", false, 0);
        assertThat(q.next(1, false).readText()).doesNotContain("1234").contains("(번호 가림)");
        assertThat(q.next(2, false).readText()).isEqualTo("비님 1,000원 후원");
    }
}
