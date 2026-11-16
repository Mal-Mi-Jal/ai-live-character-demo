package com.livecharacter.engine;

import java.util.List;
import java.util.Random;

/**
 * 캐릭터 대사의 "사실"과 기본(템플릿) 문장을 만든다.
 * 무엇에 반응할지(주제·키워드·꺼낼 기억·감정)는 여기서 코드가 정한다. LLM은 그 사실로 말투만 바꾼다.
 * LLM이 없거나, 늦거나, 검사(LineCheck)에 걸리면 이 템플릿 문장이 그대로 나간다.
 *
 * 캐릭터 (오리지널): 별똥별을 타고 지구에 막 떨어진 꼬마 별 "솔라".
 * 설정표는 resources/characters/sola.md
 */
public class LineWriter {

    public static final String CHARACTER = "솔라";

    public enum Topic { GIFT, GREETING, FOOD, GAME, TIRED, QUESTION, OTHER }

    /** 표정·음성 쪽으로 넘길 값. 이 데모에서는 아바타 이모지만 바꾼다 */
    public enum Emotion { HAPPY, EXCITED, TOUCHED, THINKING, CALM }

    // 음성으로 읽힐 대사라 짧게 (닉네임 포함 대략 35자 안팎, 초당 5.7자로 약 6초)
    private static final List<String> GIFT = List.of(
            "{nick}님 선물 고마워요! 감동함 해요!",
            "{nick}님 덕분에 솔라 반짝반짝!");
    private static final List<String> GREETING = List.of(
            "{nick}님 안녕! 반가움 많이 해요~",
            "{nick}님 어서 와요! 안녕안녕!");
    private static final List<String> FOOD = List.of(
            "{nick}님 {kw}! 솔라도 먹어 보고 싶다!",
            "{kw}? {nick}님 무슨 맛이에요?");
    private static final List<String> GAME = List.of(
            "{nick}님 {kw}! 솔라도 해 보고 싶음!",
            "{nick}님 {kw} 이기면 자랑해 줘요!");
    private static final List<String> TIRED = List.of(
            "{nick}님 수고했어요. 푹 쉬어요.",
            "{nick}님 힘내요! 별빛 나눠 줄게요.");
    private static final List<String> QUESTION = List.of(
            "{nick}님 그건… 비밀이에요! 히히.",
            "{nick}님 질문! 솔라 생각 중…");
    private static final List<String> OTHER = List.of(
            "{nick}님 말 들었어요! 고마워요~",
            "{nick}님 그거 재밌다! 더 말해 줘요.");

    static final List<String> FOOD_KW = List.of("라면", "떡볶이", "치킨", "피자", "마라탕", "김밥", "아이스크림", "밥");
    static final List<String> GAME_KW = List.of("롤", "발로란트", "마크", "배그", "게임", "랭크");
    static final List<String> GREET_KW = List.of("안녕", "하이", "ㅎㅇ", "왔어", "처음");
    static final List<String> TIRED_KW = List.of("피곤", "힘들", "졸려", "야근", "우울", "지쳤");

    /** 주제와 그 주제를 고른 키워드 (없으면 null). 팬 기억에는 음식·게임 키워드만 남긴다 */
    public record Detected(Topic topic, String keyword) {
        /** 다음에 "지난번에 ~ 얘기했죠"로 꺼내도 괜찮은 키워드인가 (피곤·우울 같은 건 기억해서 꺼내지 않는다) */
        boolean memorable() {
            return keyword != null && (topic == Topic.FOOD || topic == Topic.GAME);
        }
    }

    /** 대사 하나의 결과 */
    public record Line(String text, Emotion emotion) {
    }

    static Detected detect(String text, int gift) {
        if (gift > 0) return new Detected(Topic.GIFT, keywordIn(text));
        for (String k : FOOD_KW) if (text.contains(k)) return new Detected(Topic.FOOD, k);
        for (String k : GAME_KW) if (text.contains(k)) return new Detected(Topic.GAME, k);
        for (String k : TIRED_KW) if (text.contains(k)) return new Detected(Topic.TIRED, k);
        for (String k : GREET_KW) if (text.contains(k)) return new Detected(Topic.GREETING, k);
        if (text.contains("?")) return new Detected(Topic.QUESTION, null);
        return new Detected(Topic.OTHER, null);
    }

    /** 선물 채팅에도 음식·게임 키워드가 있으면 기억에는 남긴다 */
    private static String keywordIn(String text) {
        for (String k : FOOD_KW) if (text.contains(k)) return k;
        for (String k : GAME_KW) if (text.contains(k)) return k;
        return null;
    }

    static Emotion emotionOf(Topic t) {
        return switch (t) {
            case GIFT -> Emotion.TOUCHED;
            case GREETING -> Emotion.HAPPY;
            case FOOD, GAME -> Emotion.EXCITED;
            case TIRED -> Emotion.CALM;
            case QUESTION -> Emotion.THINKING;
            case OTHER -> Emotion.HAPPY;
        };
    }

    public Line write(String nickname, Detected d, String recall, Random rnd) {
        List<String> pool = switch (d.topic()) {
            case GIFT -> GIFT;
            case GREETING -> GREETING;
            case FOOD -> FOOD;
            case GAME -> GAME;
            case TIRED -> TIRED;
            case QUESTION -> QUESTION;
            case OTHER -> OTHER;
        };
        String line = pool.get(rnd.nextInt(pool.size()))
                .replace("{nick}", nickname)
                .replace("{kw}", d.keyword() == null ? "" : d.keyword());
        // 다시 온 팬: 대사 자체를 "알아봤어요" 한 줄로 바꾼다 (짧게 유지하려고 덧붙이지 않는다)
        if (recall != null) line = nickname + "님 또 왔네요! " + recall + " 얘기 기억나요!";
        return new Line(line, emotionOf(d.topic()));
    }
}
