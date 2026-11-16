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
            "{nick}님 덕분에 솔라 반짝반짝!",
            "와, {nick}님! 꼬리별이 막 흔들려요!");
    private static final List<String> GIFT_WITH_TOPIC = List.of(
            "{nick}님 {kw}! 선물까지 고마워요!",
            "{kw} 얘기에 선물까지! {nick}님 최고!");
    private static final List<String> GREETING = List.of(
            "{nick}님 안녕! 반가움 많이 해요~",
            "{nick}님 어서 와요! 안녕안녕!",
            "{nick}님 왔다! 솔라 기다렸어요!");
    private static final List<String> FOOD = List.of(
            "{nick}님 {kw}! 솔라도 먹어 보고 싶다!",
            "{kw}? {nick}님 무슨 맛이에요?",
            "{nick}님 {kw} 맛있었어요? 부러움!");
    private static final List<String> GAME = List.of(
            "{nick}님 {kw}! 솔라도 해 보고 싶음!",
            "{nick}님 {kw} 이기면 자랑해 줘요!",
            "{kw}! {nick}님 잘해요? 알려 줘요!");
    private static final List<String> TIRED = List.of(
            "{nick}님 수고했어요. 푹 쉬어요.",
            "{nick}님 힘내요! 별빛 나눠 줄게요.",
            "{nick}님 오늘 고생했어요. 토닥토닥.");
    private static final List<String> QUESTION = List.of(
            "{nick}님 그건… 비밀이에요! 히히.",
            "{nick}님 질문! 솔라 생각 중…",
            "음, {nick}님 그거 어려워요!");
    private static final List<String> OTHER = List.of(
            "{nick}님 말 들었어요! 고마워요~",
            "{nick}님 그거 재밌다! 더 말해 줘요.",
            "{nick}님 ㅎㅎ 솔라도 웃음 나요!");

    static final List<String> FOOD_KW = List.of("라면", "떡볶이", "치킨", "피자", "마라탕", "김밥", "아이스크림", "밥");
    static final List<String> GAME_KW = List.of("롤", "발로란트", "마크", "배그", "게임", "랭크");
    static final List<String> GREET_KW = List.of("안녕", "하이", "ㅎㅇ", "왔어", "처음");
    static final List<String> TIRED_KW = List.of("피곤", "힘들", "졸려", "야근", "우울", "지쳤");

    /** 주제와 그 주제를 고른 키워드 (없으면 null). 팬 기억에는 음식·게임 키워드만 남긴다 */
    public record Detected(Topic topic, String keyword, boolean tiredGift) {
        public Detected(Topic topic, String keyword) {
            this(topic, keyword, false);
        }

        /** 다음에 "지난번에 ~ 얘기했죠"로 꺼내도 괜찮은 키워드인가 (피곤·우울 같은 건 기억해서 꺼내지 않는다) */
        boolean memorable() {
            return keyword != null && (topic == Topic.FOOD || topic == Topic.GAME || topic == Topic.GIFT);
        }
    }

    /** 대사 하나의 결과 */
    public record Line(String text, Emotion emotion) {
    }

    static Detected detect(String text, int gift) {
        if (gift > 0) {
            String kw = keywordIn(text);
            boolean tired = kw == null && TIRED_KW.stream().anyMatch(text::contains);
            return new Detected(Topic.GIFT, kw, tired);
        }
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

    /**
     * 되읽기 대사: 이름을 부르는 대신 채팅을 한 번 읽고 반응한다 ("피자 시켰어요" 오, 피자! 솔라도 먹어 보고 싶어요.)
     * 사용자 피드백(2026-11-17): 매번 이름을 부르는 것보다 채팅 내용을 읽는 게 실제 방송처럼 자연스럽다.
     * 화면 데모에서만 켠다 (시뮬레이션 숫자를 그대로 두려고 기본은 꺼짐). 고르기는 따로 둔 난수로 해서 방의 난수 순서를 바꾸지 않는다
     */
    private boolean echo;
    private final Random styleRnd = new Random(11);
    /** 되읽을 채팅 길이 상한 (길면 다 읽기 지루해서 이름 부르는 방식으로) */
    static final int ECHO_MAX = 16;

    void setEcho(boolean echo) {
        this.echo = echo;
    }

    private static final List<String> ECHO_FOOD = List.of(
            "오, {kw}! 솔라도 {kw} 먹어 보고 싶어요.", "{kw} 맛있었겠다! 부러움 해요.", "와 {kw}! 무슨 맛인지 알려 줘요.");
    private static final List<String> ECHO_GAME = List.of(
            "{kw} 하는구나! 솔라도 구경하고 싶음.", "오 {kw}! 이기면 자랑해 줘요.");
    private static final List<String> ECHO_TIRED = List.of(
            "아이고, 오늘 고생 많았어요. 푹 쉬어요.", "토닥토닥, 솔라가 별빛 나눠 줄게요.");
    private static final List<String> ECHO_GREETING = List.of(
            "어서 와요! 솔라도 반가움 해요.", "왔구나! 오늘도 반짝반짝 놀아요.");
    private static final List<String> ECHO_QUESTION = List.of(
            "음… 그건 솔라도 궁금해요!", "히히, 그건 비밀이에요.");
    private static final List<String> ECHO_OTHER = List.of(
            "오 그렇구나! 더 얘기해 줘요.", "ㅎㅎ 솔라도 웃음 나요.", "그거 좋다!");
    /** 이름을 부를 때도 묻는 말투로 (예: OO님은 피자 드셨어요? 맛있었겠다!) */
    private static final List<String> NAME_FOOD_ASK = List.of(
            "{nick}님은 {kw} 드셨어요? 맛있었겠다!", "{nick}님 {kw}! 솔라 침 고임 해요.");

    /** 되읽기를 할지, 한다면 어떤 문장인지. 안 하면 null */
    private Line echoLine(String nickname, Detected d, String chatText) {
        if (!echo || chatText == null) return null;
        String quote = chatText.strip();
        if (quote.isEmpty() || quote.length() > ECHO_MAX) return null;
        // 절반은 되읽기, 절반은 이름 부르기 (음식이면 이름 부르기도 묻는 말투로)
        boolean doEcho = styleRnd.nextBoolean();
        if (!doEcho) {
            if (d.topic() == Topic.FOOD && styleRnd.nextBoolean()) {
                String t = pickFresh(NAME_FOOD_ASK, styleRnd).replace("{nick}", nickname).replace("{kw}", d.keyword());
                return new Line(t, Emotion.EXCITED);
            }
            return null;
        }
        List<String> pool = switch (d.topic()) {
            case FOOD -> ECHO_FOOD;
            case GAME -> ECHO_GAME;
            case TIRED -> ECHO_TIRED;
            case GREETING -> ECHO_GREETING;
            case QUESTION -> ECHO_QUESTION;
            case OTHER -> ECHO_OTHER;
            case GIFT -> null;
        };
        if (pool == null) return null;
        String reaction = pickFresh(pool, styleRnd).replace("{kw}", d.keyword() == null ? "" : d.keyword());
        return new Line("\"" + quote + "\" " + reaction, emotionOf(d.topic()));
    }

    /** 최근에 쓴 템플릿 (같은 문장 무한 반복을 막으려고) */
    private final java.util.ArrayDeque<String> recentTemplates = new java.util.ArrayDeque<>();
    private static final int AVOID_LAST = 6;

    /** 최근에 안 쓴 템플릿 중에서 고른다. 다 썼으면 아무거나 */
    private String pickFresh(List<String> pool, Random rnd) {
        List<String> fresh = pool.stream().filter(t -> !recentTemplates.contains(t)).toList();
        String t = (fresh.isEmpty() ? pool : fresh).get(rnd.nextInt((fresh.isEmpty() ? pool : fresh).size()));
        recentTemplates.addLast(t);
        while (recentTemplates.size() > AVOID_LAST) recentTemplates.pollFirst();
        return t;
    }

    public Line write(String nickname, Detected d, String recall, Random rnd) {
        return write(nickname, d, recall, rnd, null);
    }

    /** @param chatText 그 팬의 채팅 원문 (되읽기 대사에 쓴다) */
    public Line write(String nickname, Detected d, String recall, Random rnd, String chatText) {
        if (recall == null) {
            Line e = echoLine(nickname, d, chatText);
            if (e != null) return e;
        }
        List<String> pool = switch (d.topic()) {
            case GIFT -> GIFT;
            case GREETING -> GREETING;
            case FOOD -> FOOD;
            case GAME -> GAME;
            case TIRED -> TIRED;
            case QUESTION -> QUESTION;
            case OTHER -> OTHER;
        };
        if (d.tiredGift()) {
            // 피곤하다면서 선물한 팬에게 "꼬리별 흔들려요!"는 어색하다
            return new Line(nickname + "님 선물 고마워요. 오늘은 푹 쉬어요.", Emotion.CALM);
        }
        if (d.topic() == Topic.GIFT && d.keyword() != null) {
            // 선물과 함께 음식·게임 이야기를 했으면 그 이야기에 답하면서 선물도 고마워한다
            String t = pickFresh(GIFT_WITH_TOPIC, rnd).replace("{nick}", nickname).replace("{kw}", d.keyword());
            return new Line(t, Emotion.TOUCHED);
        }
        String line = pickFresh(pool, rnd)
                .replace("{nick}", nickname)
                .replace("{kw}", d.keyword() == null ? "" : d.keyword());
        // 다시 온 팬: 대사 자체를 "알아봤어요" 한 줄로 바꾼다 (짧게 유지하려고 덧붙이지 않는다)
        if (recall != null) line = nickname + "님 또 왔네요! " + recall + " 얘기 기억나요!";
        return new Line(line, emotionOf(d.topic()));
    }
}
