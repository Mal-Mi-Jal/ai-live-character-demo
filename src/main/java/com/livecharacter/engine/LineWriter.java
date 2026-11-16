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

    private static final List<String> GIFT = List.of(
            "{nick}님 선물?! 솔라 별빛이 두 배 됨 해요! 고마워요!",
            "와아, {nick}님! 이거 지구에서 '감동'이라고 하는 거 맞죠?",
            "{nick}님 덕분에 솔라 오늘 반짝반짝 최고 상태예요!");
    private static final List<String> GREETING = List.of(
            "{nick}님 안녕! 솔라도 반가움 많이 해요~",
            "어서 와요 {nick}님! 지구 인사는 이렇게 하는 거 맞죠? 안녕안녕!",
            "{nick}님 왔다! 솔라 방금 꼬리별 흔들었어요.");
    private static final List<String> FOOD = List.of(
            "{nick}님 {kw} 얘기다! 솔라 지구 음식 중에 {kw} 궁금함 1등이에요.",
            "{kw}...! 별에는 그런 거 없어요. {nick}님 무슨 맛이에요?",
            "{nick}님 때문에 {kw} 먹고 싶어짐 해요. 책임져요!");
    private static final List<String> GAME = List.of(
            "{nick}님 {kw} 해요? 솔라도 해 보고 싶다! 근데 버튼이 너무 많음…",
            "{kw}! 솔라가 하면 별 속도로 이길 자신 있어요. 아마도요. {nick}님은요?",
            "{nick}님 {kw} 이기면 솔라한테 자랑하러 와요!");
    private static final List<String> TIRED = List.of(
            "{nick}님 오늘 힘들었어요? 솔라가 별빛 조금 나눠 줄게요.",
            "{nick}님, 쉬어도 괜찮아요. 솔라 여기 계속 반짝이고 있을게요.",
            "{nick}님 수고 많았어요. 지구 사람들 진짜 대단함 해요.");
    private static final List<String> QUESTION = List.of(
            "{nick}님 질문 좋아요! 음… 솔라 생각에는요, 비밀이에요! 히히.",
            "그거 솔라도 궁금했던 거예요, {nick}님! 같이 알아봐요.",
            "{nick}님 어려운 질문 함 해요… 솔라 별 머리로 생각 중…");
    private static final List<String> OTHER = List.of(
            "{nick}님 말 들었어요! 솔라 귀는 별처럼 밝아요.",
            "오, {nick}님 그거 재밌다! 더 말해 줘요.",
            "{nick}님~ 솔라 지금 그 말 메모함 해요.");

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
        if (recall != null) line = "지난번에 " + recall + " 얘기했던 거 기억나요! " + line;
        return new Line(line, emotionOf(d.topic()));
    }
}
