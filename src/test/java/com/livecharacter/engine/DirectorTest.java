package com.livecharacter.engine;

import com.livecharacter.sim.BotAudience;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirectorTest {

    private static Chat chat(long seq, String id, String text, long at) {
        return new Chat(seq, id, "팬" + id, text, 0, at, false);
    }

    @Test
    void 떡밥이_있으면_질문하고_답을_먼저_보고_20초_뒤_정리한다() {
        Director d = new Director();
        long t = 100_000;
        Director.Plan ask = d.next(t, Map.of("라면", 6, "인사", 20), false);
        assertThat(ask.kind()).isEqualTo(Director.Kind.ASK);
        assertThat(ask.question().id()).isEqualTo("dinner"); // 인사는 떡밥으로 안 친다
        String line = d.startAsk(ask.question(), ask.preface(), t);
        assertThat(line).startsWith("지금 라면 얘기 많다!");

        Director.Plan during = d.next(t + 5_000, Map.of(), true);
        assertThat(during.kind()).isEqualTo(Director.Kind.REACT); // 질문 중엔 묶음 차례도 미룬다
        assertThat(during.prefer().test(chat(1, "a", "치킨 먹었지", t + 1_000))).isTrue();
        assertThat(during.prefer().test(chat(2, "b", "ㅋㅋㅋ", t + 1_000))).isFalse();
        assertThat(during.prefer().test(chat(3, "c", "라면", t - 1_000))).isFalse(); // 질문 전 채팅은 답이 아니다

        assertThat(d.next(t + 20_000, Map.of(), false).kind()).isEqualTo(Director.Kind.ANSWERS);
        List<Chat> recent = List.of(
                chat(1, "a", "라면 먹었어요", t + 1_000),
                chat(2, "a", "아 아니다 치킨", t + 2_000),   // 한 사람은 마지막 답만
                chat(3, "b", "치킨!", t + 3_000),
                chat(4, "c", "김밥이요", t + 4_000),
                chat(5, "d", "떡볶이", t - 5_000));          // 질문 전
        Director.Answers a = d.finishAsk(recent, Set.of("a"), t + 20_000);
        assertThat(a.counts()).isEqualTo(Map.of("치킨", 2, "김밥", 1));
        assertThat(a.total()).isEqualTo(3);
        assertThat(a.highlightNick()).isEqualTo("팬b"); // 이미 목소리로 불린 a 말고
        assertThat(a.line()).startsWith("치킨 2명, 김밥 1명! 팬b님 답 고마워요.");

        // 정리 뒤 30초는 같은 주제 채팅을 먼저 본다, 다음 질문은 60초 뒤
        Director.Plan after = d.next(t + 25_000, Map.of("치킨", 9), false);
        assertThat(after.kind()).isEqualTo(Director.Kind.REACT);
        assertThat(after.prefer().test(chat(9, "e", "피자 시켰어요", t + 24_000))).isTrue();
        // 같은 질문(저녁)은 다시 안 한다. 다른 떡밥(피곤)이면 60초 뒤 그 질문
        assertThat(d.next(t + 61_000, Map.of("치킨", 9), false).kind()).isNotEqualTo(Director.Kind.ASK);
        Director.Plan mood = d.next(t + 62_000, Map.of("피곤", 7), false);
        assertThat(mood.kind()).isEqualTo(Director.Kind.ASK);
        assertThat(mood.question().id()).isEqualTo("mood");
    }

    @Test
    void 떡밥이_없어도_8차례가_지나면_돌아가며_질문한다() {
        Director d = new Director();
        Director.Kind last = null;
        int turn = 0;
        while (last != Director.Kind.ASK && turn < 20) last = d.next(100_000 + 6_000L * turn++, Map.of(), false).kind();
        assertThat(turn).isEqualTo(Director.ASK_EVERY_TURNS);
    }

    /** 3분 라이브를 가상 시계로 돌려 진행 대본을 출력한다 (봇 300명, 템플릿 대사) */
    @Test
    void 진행_대본_3분() {
        BotAudience audience = new BotAudience(300, 42);
        LiveRoom room = new LiveRoom(PickRule.FAIR, 1, 3, 5_000, 42);
        Director d = new Director();
        long next = 3_000;
        int turn = 0, asks = 0, onTopicDuringAsk = 0, reactsDuringAsk = 0, answered = 0;
        StringBuilder script = new StringBuilder();
        for (long now = 0; now < 180_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            if (now < next) continue;
            turn++;
            Director.Plan p = d.next(now, room.hotTopics(now), turn % 4 == 0);
            String line;
            switch (p.kind()) {
                case ASK -> {
                    line = "[질문] " + d.startAsk(p.question(), p.preface(), now);
                    audience.answering(p.question().botAnswers());
                    asks++;
                }
                case ANSWERS -> {
                    Director.Answers a = d.finishAsk(room.recentChats(), room.voiceCalled(), now);
                    audience.answering(null);
                    room.ackAnswers(a.answerers());
                    answered += a.total();
                    line = "[답 " + a.total() + "명] " + a.line();
                }
                case GROUP -> {
                    LiveRoom.GroupMoment g = room.groupMoment(now);
                    line = g == null ? null : "[묶음] " + g.line();
                }
                default -> line = null;
            }
            if (line == null) {
                LiveRoom.TickResult r = room.tick(now, p.prefer());
                if (r.reactions().isEmpty()) {
                    next = now + 1_000;
                    continue;
                }
                LiveRoom.Reaction x = r.reactions().get(0);
                if (d.active() != null) {
                    reactsDuringAsk++;
                    if (d.active().bucketOf(x.chatText()) != null) onTopicDuringAsk++;
                }
                line = "\"" + x.chatText() + "\" → " + x.line();
            }
            script.append(String.format("%5.1f초  %s%n", now / 1000.0, line));
            next = now + Math.round(line.length() / VoiceModeTest.CHARS_PER_SEC * 1000) + 400;
        }
        System.out.println("진행 대본 (봇 300명, 3분, 시드 42, 템플릿 대사)");
        System.out.print(script);
        System.out.printf("질문 %d번, 답한 팬 %d명, 질문 중 목소리 반응 %d번 중 답에 반응 %d번%n",
                asks, answered, reactsDuringAsk, onTopicDuringAsk);
        assertThat(asks).isGreaterThanOrEqualTo(1);
        assertThat(answered).isGreaterThan(0);
        assertThat(onTopicDuringAsk).isEqualTo(reactsDuringAsk); // 질문 중엔 답에만 반응
    }
}
