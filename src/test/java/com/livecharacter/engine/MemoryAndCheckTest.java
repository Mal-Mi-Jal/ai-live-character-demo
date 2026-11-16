package com.livecharacter.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryAndCheckTest {

    @Test
    void 일분_넘게_지난_음식_게임_키워드만_다시_온_팬에게_꺼낸다() {
        LiveRoom room = new LiveRoom(PickRule.FAIR, 3, 1);
        room.receive("a", "에이", "오늘 저녁 라면 먹었어", 0, 0, false);
        room.receive("a", "에이", "야근해서 피곤해", 0, 1_000, false); // 피곤은 기억하지 않는다
        room.tick(3_000);

        room.receive("a", "에이", "롤 랭크 또 졌어", 0, 30_000, false);
        LiveRoom.Reaction early = room.tick(33_000).reactions().get(0);
        assertThat(early.recall()).isNull(); // 30초 전 이야기는 "지난번"이 아니다

        room.receive("a", "에이", "안녕 솔라", 0, 120_000, false);
        LiveRoom.Reaction later = room.tick(123_000).reactions().get(0);
        assertThat(later.recall()).isIn("라면", "롤");
        assertThat(later.line()).contains("지난번에 " + later.recall());
    }

    @Test
    void 기억은_닉네임이_아니라_시청자_id로_묶는다() {
        LiveRoom room = new LiveRoom(PickRule.FAIR, 3, 1);
        room.receive("a", "같은닉", "라면 최고", 0, 0, false);
        room.tick(3_000);
        room.receive("b", "같은닉", "안녕", 0, 120_000, false); // 닉네임만 같은 다른 사람
        assertThat(room.tick(123_000).reactions().get(0).recall()).isNull();
    }

    private static LiveRoom.Reaction facts(String nick, String recall) {
        return new LiveRoom.Reaction("id", nick, "안녕", 0, LineWriter.Topic.GREETING, null, recall,
                "템플릿", LineWriter.Emotion.HAPPY, "template", 0, false);
    }

    @Test
    void 대사_검사() {
        List<String> nicks = List.of("졸린고양이3", "반짝여우7");
        LiveRoom.Reaction r = facts("졸린고양이3", null);
        assertThat(LineCheck.reject("졸린고양이3님 반가움 해요!", r, nicks)).isNull();
        assertThat(LineCheck.reject("반가워요!", r, nicks)).isEqualTo("닉네임 없음");
        assertThat(LineCheck.reject("졸린고양이3님, 반짝여우7님도 안녕!", r, nicks)).isEqualTo("다른 팬 언급");
        assertThat(LineCheck.reject("졸린고양이3님 지난번에 라면 얘기했죠?", r, nicks)).isEqualTo("기억 지어냄");
        assertThat(LineCheck.reject("졸린고양이3님 5000원 고마워요", r, nicks)).isEqualTo("숫자 지어냄");
        assertThat(LineCheck.reject("졸린고양이3님 바보래요", r, nicks)).isEqualTo("금칙어·개인정보");
        assertThat(LineCheck.reject("졸린고양이3님 " + "아".repeat(100), r, nicks)).isEqualTo("너무 김");

        LiveRoom.Reaction withMem = facts("졸린고양이3", "라면");
        assertThat(LineCheck.reject("졸린고양이3님 또 왔다!", withMem, nicks)).isEqualTo("넘긴 기억을 안 씀");
        assertThat(LineCheck.reject("졸린고양이3님 지난번 라면 얘기 기억나요!", withMem, nicks)).isNull();
    }
}
