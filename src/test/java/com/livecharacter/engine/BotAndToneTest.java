package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BotAndToneTest {

    @Test
    void 짧은_키워드_오탐을_막고_자주_묻는_질문엔_세계관에_맞게_답한다() {
        assertThat(LineWriter.detect("채팅 스크롤 너무 빠르다", 0).topic()).isEqualTo(LineWriter.Topic.OTHER);
        assertThat(LineWriter.detect("롤러코스터 타고 싶다", 0).topic()).isEqualTo(LineWriter.Topic.OTHER);
        assertThat(LineWriter.detect("롤 칼바람 재밌다", 0).topic()).isEqualTo(LineWriter.Topic.GAME);
        assertThat(LineWriter.detect("밥맛없네 오늘", 0).topic()).isEqualTo(LineWriter.Topic.OTHER);
        assertThat(LineWriter.detect("저녁 국밥 먹었어요", 0).topic()).isEqualTo(LineWriter.Topic.FOOD);

        LineWriter w = new LineWriter();
        LineWriter.Line age = w.write("민트99", LineWriter.detect("솔라 몇 살이야?", 0), null, new Random(1), "솔라 몇 살이야?");
        assertThat(age.text()).contains("별 나이").doesNotContain("비밀");
        LineWriter.Line eat = w.write("민트99", LineWriter.detect("솔라 별에서는 뭐 먹어?", 0), null, new Random(1), "솔라 별에서는 뭐 먹어?");
        assertThat(eat.text()).contains("별빛");
        LineWriter.Line sea = w.write("초코집사57", LineWriter.detect("이번 휴가엔 바다 보러 가고 싶다!", 0), null, new Random(1), "이번 휴가엔 바다 보러 가고 싶다!");
        assertThat(sea.text()).contains("바다").doesNotContain("웃음");
    }

    @Test
    void 현실화_봇은_다양한_닉네임과_문장을_만들고_질문_답도_버킷에_맞는다() {
        BotAudience realistic = new BotAudience(100, 42, 0.03, true);
        Set<String> nicks = new HashSet<>();
        realistic.bots().forEach(b -> nicks.add(b.nickname()));
        assertThat(nicks).hasSize(100);

        Set<String> texts = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            for (BotAudience.Sent s : realistic.step(1000)) texts.add(s.text());
        }
        assertThat(texts.size()).isGreaterThan(40);

        // 모든 질문에 대해 realistic 봇의 답변이 Director.Question.bucketOf에 정상 매칭되는지 확인
        for (Director.Question q : Director.ROTATION) {
            realistic.answeringQuestion(q.id(), q.botAnswers());
            int matched = 0;
            for (int i = 0; i < 30; i++) {
                for (BotAudience.Sent s : realistic.step(500)) {
                    if (q.bucketOf(s.text()) != null) matched++;
                }
            }
            assertThat(matched).as("question %s", q.id()).isGreaterThan(0);
            assertThat(q.quickReplies()).isNotEmpty();
        }
    }

    @Test
    void 방송_주도_모드에서_밸런스_게임과_2부작_에피소드가_흐름대로_진행된다() {
        Director d = new Director();
        d.setShowHostMode(true);

        // 밸런스 게임: 선언 → 중간 판세 중계 → 최종 판결
        d.requestCorner("balance");
        long t = 100_000;
        assertThat(d.next(t, java.util.Map.of(), false).kind()).isEqualTo(Director.Kind.BALANCE_START);
        Director.BalanceGame bg = d.startBalance(t);
        java.util.List<Chat> votes = java.util.List.of(
                new Chat(1, "u1", "민트", bg.optionA() + " 최고!", 0, t + 2_000, false),
                new Chat(2, "u2", "초코", bg.optionB() + " 국룰이지", 0, t + 4_000, false),
                new Chat(3, "u3", "라떼", bg.optionB() + " 한 표!", 0, t + 6_000, false));
        Director.BalanceView progress = d.balanceProgress(votes, t + 10_000);
        assertThat(progress.countA()).isEqualTo(1);
        assertThat(progress.countB()).isEqualTo(2);

        assertThat(d.next(t + Director.BALANCE_MID_MS, java.util.Map.of(), false).kind()).isEqualTo(Director.Kind.BALANCE_MID);
        assertThat(d.balanceMidLine(votes)).contains(bg.optionA()).contains(bg.optionB());

        assertThat(d.next(t + Director.BALANCE_MS, java.util.Map.of(), false).kind()).isEqualTo(Director.Kind.BALANCE_END);
        Director.BalanceResult res = d.finishBalance(votes, t + Director.BALANCE_MS);
        assertThat(res.countA()).isEqualTo(1);
        assertThat(res.countB()).isEqualTo(2);
        assertThat(res.voters()).containsExactlyInAnyOrder("u1", "u2", "u3");
        assertThat(res.line()).contains(bg.winB());

        // 2부작 에피소드: 도입 → 에피소드 반응 티키타카 2회 → 결말
        d.requestCorner("episode");
        assertThat(d.next(t + 40_000, java.util.Map.of(), false).kind()).isEqualTo(Director.Kind.EPISODE_START);
        Director.Episode ep = d.startEpisode(t + 40_000);
        assertThat(ep.cheers1()).isNotEmpty();
        Director.Plan p1 = d.next(t + 45_000, java.util.Map.of(), false);
        assertThat(p1.kind()).isEqualTo(Director.Kind.REACT);
        assertThat(p1.prefer()).isNotNull();
        assertThat(d.next(t + 50_000, java.util.Map.of(), false).kind()).isEqualTo(Director.Kind.REACT);
        assertThat(d.next(t + 55_000, java.util.Map.of(), false).kind()).isEqualTo(Director.Kind.EPISODE_END);
        assertThat(d.finishEpisode().part2()).isEqualTo(ep.part2());

        // 채팅 속 탕수육 떡밥을 발견해 자연스럽게 부먹 vs 찍먹으로 연결
        Director d2 = new Director();
        d2.setShowHostMode(true);
        Director.StartedBalance sb = d2.startBalance(t + 10_000, java.util.List.of(
                new Chat(9, "u9", "별사탕", "아 오늘 탕수육 시킬까 고민중", 0, t + 8_000, false)));
        assertThat(sb.game().id()).isEqualTo("tangsuyuk");
        assertThat(sb.introLine()).contains("별사탕님");
        assertThat(d2.balanceChatReply(new Chat(10, "u10", "민트", "찍먹이 국룰이지ㅋㅋ", 0, t + 12_000, false)))
                .contains("찍먹");

        // PRE_LIVE 대기 단계에선 후원 없이 조용히 대기 채팅만 올라온다
        BotAudience pre = new BotAudience(100, 42, 0.5, true);
        pre.setPhase(BotAudience.Phase.PRE_LIVE);
        for (int i = 0; i < 40; i++) {
            for (BotAudience.Sent s : pre.step(500)) {
                assertThat(s.gift()).isZero();
            }
        }
    }
}
