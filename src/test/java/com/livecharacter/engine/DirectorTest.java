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

        assertThat(d.next(t + Director.ANSWER_MS, Map.of(), false).kind()).isEqualTo(Director.Kind.ANSWERS);
        List<Chat> recent = List.of(
                chat(1, "a", "라면 먹었어요", t + 1_000),
                chat(2, "a", "아 아니다 치킨", t + 2_000),   // 한 사람은 마지막 답만
                chat(3, "b", "치킨!", t + 3_000),
                chat(4, "c", "김밥이요", t + 4_000),
                chat(5, "d", "떡볶이", t - 5_000));          // 질문 전
        Director.Answers a = d.finishAsk(recent, Set.of("a"), t + Director.ANSWER_MS);
        assertThat(a.counts()).isEqualTo(Map.of("치킨", 2, "김밥", 1));
        assertThat(a.total()).isEqualTo(3);
        assertThat(a.highlightNick()).isEqualTo("팬b"); // 이미 목소리로 불린 a 말고
        assertThat(a.line()).startsWith("치킨 2명, 김밥 1명! 팬b님 답 고마워요.");

        // 정리 바로 다음 차례는 이어 말하기: 서로 다른 답 2개, 아직 목소리로 안 불린 팬 먼저
        assertThat(d.next(t + Director.ANSWER_MS + 2_000, Map.of(), true).kind()).isEqualTo(Director.Kind.TALK);
        Director.Talk talk = d.talkMaterial(recent, Set.of("a"));
        assertThat(talk.nicknames()).containsExactly("팬c", "팬b"); // 최근 답부터, 김밥(c)·치킨(b) / a는 이미 불림
        assertThat(talk.templateLine()).isEqualTo("팬c님은 김밥, 팬b님은 치킨! 솔라 별엔 음식이 없어서 다 궁금해요.");

        // 그 뒤 30초는 같은 주제 채팅을 먼저 본다, 다음 질문은 60초 뒤
        Director.Plan after = d.next(t + Director.ANSWER_MS + 5_000, Map.of("치킨", 9), false);
        assertThat(after.kind()).isEqualTo(Director.Kind.REACT);
        assertThat(after.prefer().test(chat(9, "e", "피자 시켰어요", t + Director.ANSWER_MS + 4_000))).isTrue();
        // 같은 질문(저녁)은 4분 안에 다시 안 한다 → 음식 떡밥이면 야식 질문으로. 피곤 떡밥이면 기분 질문
        Director.Plan food = d.next(t + 61_000, Map.of("치킨", 9), false);
        assertThat(food.kind()).isEqualTo(Director.Kind.ASK);
        assertThat(food.question().id()).isEqualTo("snack");
        Director.Plan mood = d.next(t + 62_000, Map.of("피곤", 7), false);
        assertThat(mood.kind()).isEqualTo(Director.Kind.ASK);
        assertThat(mood.question().id()).isEqualTo("mood");
    }

    @Test
    void 방송_시작은_인사부터_인사_채팅에_먼저_답하고_오늘_계획_안내_뒤_본_방송() {
        Director d = new Director();
        d.startLive(0, false);
        Director.Plan hello = d.next(1_000, Map.of("라면", 9), false);
        assertThat(hello.kind()).isEqualTo(Director.Kind.HELLO);
        assertThat(hello.preface()).startsWith("안녕하세요");
        Director.Plan greet = d.next(5_000, Map.of("라면", 9), false); // 떡밥이 있어도 시작 구간엔 질문 안 함
        assertThat(greet.kind()).isEqualTo(Director.Kind.REACT);
        assertThat(greet.prefer().test(chat(1, "a", "안녕 솔라!", 4_000))).isTrue();
        assertThat(d.next(21_000, Map.of(), false).preface()).startsWith("오늘은");
        Director.Plan main = d.next(41_000, Map.of("라면", 9), false);
        assertThat(main.kind()).isEqualTo(Director.Kind.ASK); // 시작 구간이 끝나면 바로 첫 질문
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
    void 진행_대본_5분() {
        BotAudience audience = new BotAudience(300, 42);
        LiveRoom room = new LiveRoom(PickRule.FAIR, 1, 3, 5_000, 42);
        Director d = new Director();
        long next = 3_000;
        int turn = 0, asks = 0, onTopicDuringAsk = 0, reactsDuringAsk = 0, answered = 0;
        StringBuilder script = new StringBuilder();
        for (long now = 0; now < 300_000; now += 100) {
            for (BotAudience.Sent s : audience.step(100)) room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
            if (now < next) continue;
            turn++;
            Director.Plan p = d.next(now, room.hotTopics(now), turn % 7 == 0);
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
                case TALK -> line = "[이어 말하기] " + d.talkMaterial(room.recentChats(), room.voiceCalled()).templateLine();
                case STORY -> line = "[혼잣말] " + d.storyLine();
                case GROUP -> {
                    LiveRoom.GroupMoment g = room.groupMoment(now, 180_000, true);
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
        System.out.println("진행 대본 (봇 300명, 5분, 시드 42, 템플릿 대사)");
        System.out.print(script);
        System.out.printf("질문 %d번, 답한 팬 %d명, 질문 중 목소리 반응 %d번 중 답에 반응 %d번%n",
                asks, answered, reactsDuringAsk, onTopicDuringAsk);
        assertThat(asks).isGreaterThanOrEqualTo(1);
        assertThat(answered).isGreaterThan(0);
        assertThat(onTopicDuringAsk).isEqualTo(reactsDuringAsk); // 질문 중엔 답에만 반응
    }
}
