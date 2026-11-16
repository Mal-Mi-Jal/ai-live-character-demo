package com.livecharacter.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepetitionTest {

    static List<String> lines(String resource) throws IOException {
        try (InputStream in = RepetitionTest.class.getResourceAsStream(resource)) {
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"))
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        }
    }

    @Test
    void 예시_1개_프롬프트_20줄의_반복_기준선() throws IOException {
        List<String> base = lines("/talk-probe-2026-11-16-newprompt.txt");
        double rate = LineCheck.repetitionRate(base);
        System.out.printf("기준선(예시 1개 프롬프트, 실측 20줄): 앞 5줄과 같은 표현 %.0f%% (%d줄 중)%n", rate * 100, base.size() - 1);
        assertThat(base).hasSize(20);
        assertThat(rate).isGreaterThan(0.5); // 대부분 같은 틀이었다
    }

    @Test
    void 템플릿도_질문마다_틀_3개를_돌아가며_서로_안_겹친다() {
        List<Chat> chats = List.of(new Chat(1, "a", "라면왕7", "", 0, 0, false), new Chat(2, "b", "치킨러버", "", 0, 0, false));
        for (Director.Question q : Director.ROTATION) {
            List<String> b = q.id().equals("mood") ? List.of("좋았던 분", "피곤한 분")
                    : q.id().equals("game") ? List.of("롤", "배그") : List.of("라면", "치킨");
            List<String> three = List.of(Director.talkTemplate(q, b, chats, 0), Director.talkTemplate(q, b, chats, 1),
                    Director.talkTemplate(q, b, chats, 2));
            three.forEach(t -> System.out.println("  " + q.id() + ": " + t));
            assertThat(LineCheck.repetitionRate(three)).isZero();
        }
    }

    @Test
    void 이어_말하기가_최근_틀을_되풀이하면_템플릿으로() {
        Director.Talk t = new Director.Talk(Director.DINNER,
                List.of(new Chat(1, "a", "라면왕7", "라면", 0, 0, false), new Chat(2, "b", "치킨러버", "치킨", 0, 0, false)),
                "템플릿 대사");
        java.util.function.Function<String, com.livecharacter.llm.HaikuLineWriter.TalkResult> check = line ->
                com.livecharacter.llm.HaikuLineWriter.checkTalkForTest(t, line,
                        List.of("다른팬1님은 피자, 다른팬2님은 김밥이래요! 솔라 별엔 음식이 없어서 다 궁금함 해요."));
        assertThat(check.apply("라면왕7님은 라면, 치킨러버님은 치킨! 솔라 별엔 먹을 게 없어서 다 궁금함 해요.").rejected()).isEqualTo("표현 반복");
        assertThat(check.apply("라면왕7님은 라면, 치킨러버님은 치킨! 여러분은 국물파예요, 바삭파예요?").source()).isEqualTo("llm");
    }

    @Test
    void 닉네임과_띄어쓰기는_빼고_비교한다() {
        assertThat(LineCheck.repeats("라면왕7님은 라면! 솔라 별엔 먹을 게 없어서 다 궁금함 해요.",
                List.of("치킨러버님은 치킨! 솔라 별엔 먹을게 없어서 다 궁금함 해요"))).isTrue();
        assertThat(LineCheck.repeats("라면왕7님은 라면, 치킨러버님은 치킨이래요! 여러분은 국물파예요, 바삭파예요?",
                List.of("수달님은 롤, 여우님은 발로란트! 솔라 별엔 게임이 없어서 다 신기함 해요."))).isFalse();
        // 닉네임이 같아도 그건 반복이 아니다
        assertThat(LineCheck.repeats("졸린고양이222님 안녕!", List.of("졸린고양이222님은 롤!"))).isFalse();
    }
}
