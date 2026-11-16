package com.livecharacter.engine;

import java.util.List;
import java.util.Random;

/**
 * 캐릭터 대사를 만든다. 1일차는 템플릿 — 무엇에 반응할지(주제)는 코드가 키워드로 정하고,
 * 문장은 캐릭터 말투 템플릿에서 고른다. 나중에 로컬 LLM으로 바꿔도 "주제·사실은 코드, 말투만 LLM" 원칙은 같다.
 *
 * 캐릭터 (오리지널): 별똥별을 타고 지구에 막 떨어진 꼬마 별 "솔라".
 * 한국어를 배우는 중이라 말끝이 조금 어설프고("~함 해요"), 자신만만한 척하지만 금방 감동한다.
 */
public class LineWriter {

    public static final String CHARACTER = "솔라";

    enum Topic { GIFT, GREETING, FOOD, GAME, TIRED, QUESTION, OTHER }

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
            "{kw}! 솔라가 하면 별 속도로 이길 자신 있어요. 아마도요.",
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

    private static final List<String> FOOD_KW = List.of("라면", "떡볶이", "치킨", "피자", "마라탕", "김밥", "아이스크림", "밥");
    private static final List<String> GAME_KW = List.of("롤", "발로란트", "마크", "배그", "게임", "랭크");
    private static final List<String> GREET_KW = List.of("안녕", "하이", "ㅎㅇ", "왔어", "처음");
    private static final List<String> TIRED_KW = List.of("피곤", "힘들", "졸려", "야근", "우울", "지쳤");

    /** 주제와 그 주제를 고른 키워드 (없으면 null) — 2일차 팬 기억에서도 이 키워드를 쓴다 */
    record Detected(Topic topic, String keyword) {
    }

    static Detected detect(Chat c) {
        if (c.gift() > 0) return new Detected(Topic.GIFT, null);
        String t = c.text();
        for (String k : FOOD_KW) if (t.contains(k)) return new Detected(Topic.FOOD, k);
        for (String k : GAME_KW) if (t.contains(k)) return new Detected(Topic.GAME, k);
        for (String k : TIRED_KW) if (t.contains(k)) return new Detected(Topic.TIRED, k);
        for (String k : GREET_KW) if (t.contains(k)) return new Detected(Topic.GREETING, k);
        if (t.contains("?")) return new Detected(Topic.QUESTION, null);
        return new Detected(Topic.OTHER, null);
    }

    public String write(Chat c, Random rnd) {
        Detected d = detect(c);
        List<String> pool = switch (d.topic()) {
            case GIFT -> GIFT;
            case GREETING -> GREETING;
            case FOOD -> FOOD;
            case GAME -> GAME;
            case TIRED -> TIRED;
            case QUESTION -> QUESTION;
            case OTHER -> OTHER;
        };
        String line = pool.get(rnd.nextInt(pool.size()));
        return line.replace("{nick}", c.nickname()).replace("{kw}", d.keyword() == null ? "" : d.keyword());
    }
}
