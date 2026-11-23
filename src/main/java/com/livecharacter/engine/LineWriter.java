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

    /** 표정·음성 쪽으로 넘길 값. 화면에서는 SVG 표정·오라·음성 톤으로 이어진다 */
    public enum Emotion { HAPPY, EXCITED, TOUCHED, THINKING, CALM }

    // 음성으로 읽힐 대사라 짧게 (닉네임 포함 대략 35자 안팎, 초당 5.7자로 약 6초)
    private static final List<String> GIFT = List.of(
            "{nick}님 선물 고마워요! 반짝반짝!",
            "우와, {nick}님! 꼬리별이 막 흔들려요!",
            "{nick}님 덕분에 오늘 별빛 충전 완료!");
    private static final List<String> GIFT_WITH_TOPIC = List.of(
            "{nick}님 {kw} 얘기에 선물까지! 고마워요!",
            "우와, {kw}에 선물까지! {nick}님 최고예요!");
    private static final List<String> GREETING = List.of(
            "{nick}님 안녕! 오늘도 반짝반짝 놀아요~",
            "{nick}님 어서 와요! 솔라 기다렸어요!",
            "반짝! {nick}님 와 줘서 반가워요!");
    private static final List<String> FOOD = List.of(
            "{nick}님 {kw}! 지구에서 제일 맛있는 거예요?",
            "{kw}? {nick}님 어떤 맛인지 궁금해요!",
            "{nick}님 {kw} 드셨구나! 냄새만 맡아 보고 싶다~");
    private static final List<String> GAME = List.of(
            "{nick}님 {kw}! 솔라도 옆에서 구경하고 싶어요!",
            "{nick}님 {kw} 이기면 꼭 자랑해 줘요!",
            "{kw}! {nick}님 잘해요? 솔라한테도 알려 줘요!");
    private static final List<String> TIRED = List.of(
            "{nick}님 오늘 고생 많았어요. 푹 쉬어요.",
            "{nick}님 힘내요! 솔라가 별빛 나눠 줄게요.",
            "{nick}님 수고했어요. 여기선 잠깐 쉬어 가요.");
    private static final List<String> QUESTION = List.of(
            "{nick}님 그건… 별빛 비밀이에요! 히히.",
            "음, {nick}님 어려운 질문! 솔라 고민 중이에요~",
            "{nick}님 궁금한 게 많네요! 솔라도 알아볼게요!");
    private static final List<String> OTHER = List.of(
            "{nick}님 말 잘 들었어요! 더 얘기해 줘요~",
            "우와 {nick}님, 지구 얘기는 들을수록 신기해요!",
            "{nick}님 채팅 잘 보고 있어요! 반짝!");

    static final List<String> FOOD_KW = List.of("라면", "떡볶이", "치킨", "피자", "마라탕", "김밥", "아이스크림", "초밥", "국밥", "파스타", "밥");
    static final List<String> GAME_KW = List.of("롤", "발로란트", "마크", "배그", "게임", "랭크");
    static final List<String> GREET_KW = List.of("안녕", "하이", "ㅎㅇ", "왔어", "처음", "반가");
    static final List<String> TIRED_KW = List.of("피곤", "힘들", "졸려", "졸리", "야근", "우울", "지쳤");

    /** "롤"·"밥"처럼 짧은 키워드가 다른 단어에 섞여 오탐되는 경우를 막는다 */
    private static final List<String> NOT_LOL = List.of("스크롤", "컨트롤", "롤러", "롤빵", "롤케이크", "트롤", "패트롤");
    private static final List<String> NOT_BOB = List.of("밥맛", "밥그릇");

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

    private static boolean hasKeyword(String text, String kw) {
        if (!text.contains(kw)) return false;
        if (kw.equals("롤")) {
            String stripped = text;
            for (String ex : NOT_LOL) stripped = stripped.replace(ex, "");
            return stripped.contains("롤");
        }
        if (kw.equals("밥")) {
            String stripped = text;
            for (String ex : NOT_BOB) stripped = stripped.replace(ex, "");
            return stripped.contains("밥");
        }
        return true;
    }

    static Detected detect(String text, int gift) {
        if (gift > 0) {
            String kw = keywordIn(text);
            boolean tired = kw == null && TIRED_KW.stream().anyMatch(text::contains);
            return new Detected(Topic.GIFT, kw, tired);
        }
        for (String k : FOOD_KW) if (hasKeyword(text, k)) return new Detected(Topic.FOOD, k);
        for (String k : GAME_KW) if (hasKeyword(text, k)) return new Detected(Topic.GAME, k);
        for (String k : TIRED_KW) if (text.contains(k)) return new Detected(Topic.TIRED, k);
        for (String k : GREET_KW) if (text.contains(k)) return new Detected(Topic.GREETING, k);
        if (text.contains("?")) return new Detected(Topic.QUESTION, null);
        return new Detected(Topic.OTHER, null);
    }

    /** 선물 채팅에도 음식·게임 키워드가 있으면 기억에는 남긴다 */
    private static String keywordIn(String text) {
        for (String k : FOOD_KW) if (hasKeyword(text, k)) return k;
        for (String k : GAME_KW) if (hasKeyword(text, k)) return k;
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
     * 자주 나오는 질문 패턴에는 세계관에 맞게 직접 답한다 (무조건 "비밀이에요"로 회피하지 않게).
     * @param nick 닉네임 (되읽기 모드면 null)
     */
    private static Line specificQuestion(String nick, String text) {
        if (text == null) return null;
        String head = nick == null ? "" : nick + "님, ";
        if (text.contains("몇 살") || text.contains("몇살") || text.contains("나이")) {
            return new Line(head + "솔라는 별 나이라 지구 숫자로는 몰라요! 히히.", Emotion.HAPPY);
        }
        if (text.contains("뭐 먹") || text.contains("별에서") || text.contains("별빛")) {
            return new Line(head + "솔라 별에선 아침마다 별빛 한 모금씩 마셔요!", Emotion.EXCITED);
        }
        if (text.contains("노래") || text.contains("불러")) {
            return new Line(head + "노래요? 솔라 아직 지구 음정 연습 중이에요~", Emotion.THINKING);
        }
        if (text.contains("추천") || text.contains("뭐먹")) {
            return new Line(head + "추천이요? 솔라는 이름 귀여운 떡볶이에 한 표!", Emotion.EXCITED);
        }
        if (text.contains("기분") || text.contains("어때") || text.contains("뭐해")) {
            return new Line(head + "여러분이랑 수다 떠니까 꼬리별이 붕 떠 있어요!", Emotion.HAPPY);
        }
        if (text.contains("어디서") || text.contains("고향")) {
            return new Line(head + "어젯밤 별똥별 타고 지구에 똑 떨어졌어요!", Emotion.EXCITED);
        }
        return null;
    }

    /**
     * 음식·게임·피곤·인사 외의 일상 채팅(바다, 날씨, 월요일, 탕수육, 고양이, 웃음, 칭찬 등)에
     * 엉뚱한 범용 대사가 나가지 않도록 맥락에 맞게 반응한다.
     */
    private static Line specificOther(String nick, String text) {
        if (text == null) return null;
        String head = nick == null ? "" : nick + "님, ";
        if (text.contains("바다") || text.contains("휴가") || text.contains("여행")) {
            return new Line(head + "바다 얘기다! 밤바다에 별빛 비치면 엄청 예쁘대요!", Emotion.EXCITED);
        }
        if (text.contains("탕수육")) {
            return new Line(head + "탕수육이요? 부먹이에요 찍먹이에요? 솔라도 궁금해요!", Emotion.EXCITED);
        }
        if (text.contains("민트초코") || text.contains("민초")) {
            return new Line(head + "민트초코! 상쾌한 초코 맛이라던데 솔라도 궁금해요!", Emotion.EXCITED);
        }
        if (text.contains("에어컨") || text.contains("이불") || text.contains("날씨") || text.contains("춥다") || text.contains("추워") || text.contains("더워")) {
            return new Line(head + "지구 날씨는 계절마다 바뀌어서 신기해요! 감기 조심해요~", Emotion.HAPPY);
        }
        if (text.contains("월요일") || text.contains("출근") || text.contains("퇴근")) {
            return new Line(head + "오늘도 고생 많았어요! 솔라가 별빛 에너지 나눠 줄게요.", Emotion.CALM);
        }
        if (text.contains("시험 끝") || text.contains("합격") || text.contains("생일")) {
            return new Line("우와 " + head + "축하해요! 오늘 별빛 파티 열어야겠네요!", Emotion.EXCITED);
        }
        if (text.contains("고양이") || text.contains("강아지") || text.contains("냥이")) {
            return new Line(head + "지구 동물 얘기다! 솔라도 꼬리별이랑 같이 만나 보고 싶어요!", Emotion.EXCITED);
        }
        if (text.contains("커피") || text.contains("아아") || text.contains("라떼")) {
            return new Line(head + "지구인들은 커피라는 마법 물약으로 힘을 낸다면서요?", Emotion.THINKING);
        }
        if (text.contains("올빼미") || text.contains("새벽")) {
            return new Line(head + "밤에 더 쌩쌩한 편이에요? 솔라도 별이라 밤이 제일 좋아요!", Emotion.HAPPY);
        }
        if (text.contains("귀여") || text.contains("최고") || text.contains("좋다") || text.contains("재밌") || text.contains("중독") || text.contains("편해")) {
            return new Line("우와 " + head + "그 말 들으니까 꼬리별이 막 흔들려요!", Emotion.TOUCHED);
        }
        if (text.contains("우주") || text.contains("별빛") || text.contains("꼬리별") || text.contains("라디오") || text.contains("출석")) {
            return new Line(head + "별빛 라디오에 함께해 줘서 고마워요! 오늘도 반짝!", Emotion.TOUCHED);
        }
        if (text.contains("ㅋㅋ") || text.contains("ㅎㅎ")) {
            return new Line(head + "ㅎㅎ 같이 웃으니까 솔라도 신나요!", Emotion.HAPPY);
        }
        return null;
    }

    /**
     * 되읽기 대사: 이름을 부르는 대신 채팅을 한 번 읽고 반응한다 ("피자 시켰어요" 우와 피자! 어떤 맛인지 솔라도 궁금해요.)
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
            "우와 {kw}! 어떤 맛인지 솔라도 궁금해요.",
            "{kw} 맛있었겠다! 지구 음식은 다 신기해요.",
            "오 {kw}! 솔라는 아직 그림으로만 봤어요.");
    private static final List<String> ECHO_GAME = List.of(
            "{kw} 하는구나! 솔라도 옆에서 구경하고 싶어요.",
            "오 {kw}! 이기면 꼭 자랑해 줘요!");
    private static final List<String> ECHO_TIRED = List.of(
            "아이고, 오늘 고생 많았어요. 푹 쉬어요.",
            "토닥토닥, 솔라가 별빛 담요 덮어 줄게요.");
    private static final List<String> ECHO_GREETING = List.of(
            "어서 와요! 오늘도 반짝반짝 놀아요.",
            "왔구나! 솔라 기다리고 있었어요~");
    private static final List<String> ECHO_QUESTION = List.of(
            "음… 그건 솔라도 궁금한데요?",
            "히히, 그건 별빛 비밀이에요!");
    private static final List<String> ECHO_OTHER = List.of(
            "오 그렇구나! 더 얘기해 줘요~",
            "우와, 지구 얘기는 들을수록 신기해요!",
            "그거 재밌네요! 솔라도 메모했어요~");
    /** 이름을 부를 때도 묻는 말투로 (예: OO님은 피자 드셨어요? 맛있었겠다!) */
    private static final List<String> NAME_FOOD_ASK = List.of(
            "{nick}님은 {kw} 드셨어요? 맛있었겠다!",
            "{nick}님 {kw}! 별빛보다 맛있어요?");

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
        if (d.topic() == Topic.QUESTION) {
            Line spec = specificQuestion(null, quote);
            if (spec != null) return new Line("\"" + quote + "\" " + spec.text(), spec.emotion());
        }
        if (d.topic() == Topic.OTHER) {
            Line spec = specificOther(null, quote);
            if (spec != null) return new Line("\"" + quote + "\" " + spec.text(), spec.emotion());
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
        if (d.tiredGift()) {
            // 피곤하다면서 선물한 팬에게 "꼬리별 흔들려요!"는 어색하다
            return new Line(nickname + "님 선물 고마워요. 오늘은 푹 쉬어요.", Emotion.CALM);
        }
        if (d.topic() == Topic.GIFT && d.keyword() != null) {
            // 선물과 함께 음식·게임 이야기를 했으면 그 이야기에 답하면서 선물도 고마워한다
            String t = pickFresh(GIFT_WITH_TOPIC, rnd).replace("{nick}", nickname).replace("{kw}", d.keyword());
            return new Line(t, Emotion.TOUCHED);
        }
        if (recall == null && d.topic() == Topic.QUESTION) {
            Line spec = specificQuestion(nickname, chatText);
            if (spec != null) return spec;
        }
        if (recall == null && d.topic() == Topic.OTHER) {
            Line spec = specificOther(nickname, chatText);
            if (spec != null) return spec;
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
        String line = pickFresh(pool, rnd)
                .replace("{nick}", nickname)
                .replace("{kw}", d.keyword() == null ? "" : d.keyword());
        // 다시 온 팬: 대사 자체를 "알아봤어요" 한 줄로 바꾼다 (짧게 유지하려고 덧붙이지 않는다)
        if (recall != null) line = nickname + "님 또 왔네요! " + recall + " 얘기 기억나요!";
        return new Line(line, emotionOf(d.topic()));
    }
}
