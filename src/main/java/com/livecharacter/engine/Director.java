package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 진행자: 다음 차례에 무엇을 할지 정한다 (순수 Java, 시계는 밖에서 받음).
 *
 * 사람 스트리머처럼 "채팅 하나에 답하기"만 반복하지 않고,
 *  1) 채팅에서 떡밥(많은 사람이 같은 이야기)을 보면 그 주제로 질문을 던지고
 *  2) ANSWER_MS 동안 그 질문에 대한 답 위주로 반응하다가
 *  3) 답을 모아 "라면 5명, 치킨 3명!"처럼 정리하고
 *  4) 잠시 그 주제를 이어 간다 (TOPIC_MS 동안 같은 주제 채팅을 먼저 본다)
 *
 * 무엇을 언제 물을지, 답을 몇 명이 했는지는 코드가 정하고 센다. 문장은 템플릿 (숫자가 들어가서).
 * 스프링 비유: 상태를 가진 도메인 서비스. 잠금은 바깥(LiveService가 차례를 한 번에 하나만 진행)에 기댄다.
 */
public class Director {

    public enum Kind { REACT, ASK, ANSWERS, TALK, STORY, GROUP, HELLO }

    /**
     * 진행자가 던지는 질문. buckets = 답 칸 → 그 칸으로 셀 키워드들.
     * botAnswers = 데모 봇이 이 질문에 답할 때 쓰는 문장 (실제 서비스에는 없는 부분)
     */
    public record Question(String id, String label, String ask, Map<String, List<String>> buckets,
                           List<String> botAnswers, String comment) {

        /** 이 채팅이 어느 답 칸인지 (없으면 null) */
        public String bucketOf(String text) {
            for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
                for (String kw : e.getValue()) if (text.contains(kw)) return e.getKey();
            }
            return null;
        }
    }

    /** 다음 차례 계획. prefer = 목소리로 답할 채팅을 고를 때 먼저 볼 채팅 (없으면 null) */
    public record Plan(Kind kind, Question question, Predicate<Chat> prefer, String preface) {
    }

    /** 답 정리 결과 (인원은 코드가 셈) */
    public record Answers(Question question, Map<String, Integer> counts, int total,
                          List<String> answerers, String highlightNick, String line) {
    }

    /**
     * 이어 말하기 재료: 지금 주제 + 코드가 고른 답 채팅 2개 + 템플릿 대사 (LLM이 없거나 검사에 걸리면 이걸 말한다).
     * 고른 채팅의 닉네임만 대사에 나올 수 있다.
     */
    public record Talk(Question question, List<Chat> chats, String templateLine) {
        public List<String> nicknames() {
            return chats.stream().map(Chat::nickname).toList();
        }
    }

    /** 지금 진행 상태 (화면 표시용) */
    public record Status(String topic, boolean asking, long remainingMs, String ask) {
    }

    public static final long ANSWER_MS = 20_000;
    public static final long TOPIC_MS = 30_000;
    /** 질문과 질문 사이 최소 간격 */
    public static final long ASK_GAP_MS = 60_000;
    /** 떡밥이 없어도 이만큼 차례가 지나면 질문을 던진다 */
    public static final int ASK_EVERY_TURNS = 8;
    /** 이 인원 이상이 최근 30초에 같은 이야기를 하면 떡밥 */
    public static final int HOT_MIN = 5;

    static final Question DINNER = new Question("dinner", "저녁 메뉴",
            "다들 오늘 저녁 뭐 먹었어요? 솔라한테 알려 줘요!",
            buckets(LineWriter.FOOD_KW),
            List.of("저녁 라면 먹었어요", "치킨 먹었지", "떡볶이요!", "피자 시켰어요", "마라탕 먹음", "김밥이요", "그냥 밥 먹었어요"),
            "솔라도 언젠가 꼭 먹어 볼래요!");
    static final Question GAME = new Question("game", "요즘 하는 게임",
            "요즘 무슨 게임 해요? 솔라 추천 받아요!",
            buckets(List.of("롤", "발로란트", "마크", "배그")),
            List.of("롤 해요", "발로란트!", "마크 하는 중", "배그 함", "요즘 롤만 해요"),
            "솔라는 버튼 많은 게 무서움 해요…");
    static final Question MOOD = new Question("mood", "오늘 기분",
            "오늘 하루 어땠어요? 좋았어요, 피곤했어요?",
            orderedBuckets(new String[][]{
                    {"좋았던 분", "좋았", "좋아", "신나", "행복", "최고"},
                    {"피곤한 분", "피곤", "힘들", "졸려", "야근", "지쳤"}}),
            List.of("좋았어요!", "완전 신나요", "피곤해요…", "야근해서 힘들어", "오늘 최고였음", "졸려요"),
            "다들 오늘도 수고 많았어요!");
    static final Question WEEKEND = new Question("weekend", "주말 계획",
            "이번 주말에 뭐 해요? 솔라한테 알려 줘요!",
            orderedBuckets(new String[][]{
                    {"집콕", "집", "쉬", "누워", "잠"}, {"여행", "여행", "바다", "놀러"}, {"공부", "공부", "시험", "과제"},
                    {"약속", "친구", "약속", "데이트"}, {"게임", "게임", "롤"}}),
            List.of("집에서 쉴래요", "친구 만나요", "바다 놀러 가요", "시험 공부해야 돼요", "게임 할 거예요", "그냥 누워 있을 듯"),
            "솔라는 주말에 별똥별 산책 함 해요!");
    static final Question PET = new Question("pet", "같이 사는 동물",
            "다들 집에 같이 사는 동물 있어요?",
            orderedBuckets(new String[][]{
                    {"고양이", "고양이", "냥"}, {"강아지", "강아지", "멍"}, {"햄스터", "햄스터"},
                    {"물고기", "물고기", "금붕어"}, {"없음", "없어", "없음"}}),
            List.of("고양이 두 마리요", "강아지 키워요", "햄스터!", "금붕어 있어요", "없어요ㅠ", "냥이 있어요"),
            "솔라는 꼬리별이 반려동물이에요!");
    static final Question SEASON = new Question("season", "좋아하는 계절",
            "다들 무슨 계절 제일 좋아해요?",
            orderedBuckets(new String[][]{{"봄", "봄"}, {"여름", "여름"}, {"가을", "가을"}, {"겨울", "겨울"}}),
            List.of("봄이 좋아요", "여름!", "가을이 최고", "겨울 좋아해요", "무조건 여름"),
            "솔라 별엔 계절이 없어서 다 신기함 해요.");
    static final Question MUSIC = new Question("music", "요즘 듣는 노래",
            "요즘 무슨 노래 자주 들어요?",
            orderedBuckets(new String[][]{
                    {"발라드", "발라드"}, {"아이돌", "아이돌", "케이팝"}, {"힙합", "힙합"},
                    {"애니 노래", "애니", "OST"}, {"밴드", "밴드", "록"}}),
            List.of("발라드요", "아이돌 노래", "힙합 들어요", "애니 OST!", "밴드 음악"),
            "솔라는 별빛 소리만 들어 봤어요!");
    static final Question SNACK = new Question("snack", "야식",
            "지금 야식 땡기는 사람? 뭐 먹고 싶어요?",
            orderedBuckets(new String[][]{
                    {"치킨", "치킨"}, {"라면", "라면"}, {"피자", "피자"}, {"떡볶이", "떡볶이"}, {"족발", "족발", "보쌈"}}),
            List.of("치킨 땡긴다", "라면 끓일까", "피자!", "떡볶이 먹고 싶다", "족발이요"),
            "솔라도 야식 구경 함 해요!");
    static final List<Question> ROTATION = List.of(DINNER, MOOD, GAME, WEEKEND, PET, SEASON, MUSIC, SNACK);
    /** 다 물어본 질문도 이만큼 지나면 다시 물을 수 있다 */
    public static final long REASK_MS = 240_000;
    /** 질문할 게 없을 때 이만큼 차례마다 솔라 자기 이야기 한 줄 */
    public static final int STORY_EVERY = 6;

    /**
     * 방송 시작 구간: 실제 방송처럼 인사 → 인사 채팅에 먼저 답하기 → 오늘 할 이야기 안내 → 본 방송.
     * 사용자 피드백(2026-11-17): "진짜 방송 흐름처럼, 처음 켜지면 인사부터". startLive를 부르지 않으면(시뮬레이션) 이 구간은 없다
     */
    public static final long OPENING_MS = 40_000;
    static final String HELLO_LINE = "안녕하세요 여러분! 솔라의 별빛 라디오 시작함 해요!";
    static final String AGENDA_LINE = "오늘은 여러분 하루 얘기 많이 듣고 싶어요. 채팅 많이 해 줘요!";
    private long liveStartAt = Long.MIN_VALUE;
    private int helloStep;

    /**
     * 방송을 시작한다. 시작 구간 동안은 질문을 하지 않고, 끝나면 바로 첫 질문을 할 수 있게 한다.
     * @param greeted 이미 인사를 했는가 (다음 라이브는 지난 라이브 요약 오프닝으로 인사를 대신한다)
     */
    public synchronized void startLive(long nowMs, boolean greeted) {
        liveStartAt = nowMs;
        helloStep = greeted ? 1 : 0;
        lastAskAt = nowMs + OPENING_MS - ASK_GAP_MS;
        turnsSinceAsk = 0;
    }

    /** 시작 구간 중인가 (화면 표시용) */
    public synchronized boolean inOpening(long nowMs) {
        return liveStartAt != Long.MIN_VALUE && nowMs - liveStartAt < OPENING_MS;
    }

    /** 솔라 자기 이야기 (설정표 바탕, 템플릿). 채팅만 읽지 않고 캐릭터가 먼저 말을 거는 차례 */
    static final List<String> STORIES = List.of(
            "오늘 별똥별 타고 오다가 구름에 살짝 걸렸어요. 꼬리별이 아직 축축함 해요!",
            "지구 사람들은 별 보면서 소원 빈다면서요? 솔라는 별인데… 누구한테 빌어야 해요?",
            "아까 처음으로 바람을 만져 봤어요. 차갑고 간지러움 해요!",
            "솔라 고향 별에선 인사 대신 반짝 두 번 해요. 여러분도 채팅으로 해 볼래요?",
            "지구 말 공부 중인데 '배고프다'가 제일 어려워요. 솔라는 배가 없거든요!",
            "채팅 보다가 깨달았어요. 지구엔 고양이 키우는 사람이 진짜 많네요?",
            "솔라 꿈은 지구 음식 백 가지 구경하기예요. 오늘은 몇 개나 채웠을까요?",
            "별빛 라디오 오길 잘했다… 여러분 말 들으면 꼬리별이 막 따뜻해져요.");

    private Question active;
    private long askedAt;
    private long lastAskAt = -ASK_GAP_MS;
    private int turnsSinceAsk;
    private int rotation;
    /** 질문 id → 마지막으로 던진 시각 (같은 질문은 REASK_MS 동안 다시 안 한다) */
    private final Map<String, Long> askedAt2 = new LinkedHashMap<>();
    private int turnsSinceStory;
    private int storyTurn;
    private Question topic;
    private long topicUntil;
    /** 답 정리 바로 다음 차례에 그 주제로 이어 말하기를 한 번 한다 */
    private boolean talkPending;
    /** 이어 말하기 템플릿을 돌아가며 쓰려고 센다 */
    private int talkCount;
    private long topicAskedAt;

    /**
     * @param hot 최근 30초에 주제별로 말한 팬 수 (LiveRoom.hotTopics)
     * @param groupTurn 이번 차례가 묶음 인정 차례인가 (질문 중이 아닐 때만 쓴다)
     */
    public synchronized Plan next(long nowMs, Map<String, Integer> hot, boolean groupTurn) {
        if (liveStartAt != Long.MIN_VALUE && nowMs - liveStartAt < OPENING_MS) {
            if (helloStep == 0) {
                helloStep = 1;
                return new Plan(Kind.HELLO, null, null, HELLO_LINE);
            }
            if (helloStep == 1 && nowMs - liveStartAt >= OPENING_MS / 2) {
                helloStep = 2;
                return new Plan(Kind.HELLO, null, null, AGENDA_LINE);
            }
            // 시작 구간엔 인사 채팅에 먼저 답한다
            return new Plan(Kind.REACT, null,
                    c -> LineWriter.detect(c.text(), c.gift()).topic() == LineWriter.Topic.GREETING, null);
        }
        if (active != null) {
            if (nowMs - askedAt >= ANSWER_MS) return new Plan(Kind.ANSWERS, active, null, null);
            Question q = active;
            long since = askedAt;
            return new Plan(Kind.REACT, q, c -> c.receivedAtMs() >= since && q.bucketOf(c.text()) != null, null);
        }
        if (talkPending && topic != null) {
            talkPending = false;
            return new Plan(Kind.TALK, topic, null, null);
        }
        turnsSinceAsk++;
        if (nowMs - lastAskAt >= ASK_GAP_MS) {
            String hotLabel = hottest(hot);
            Question hotQ = hotLabel == null ? null : questionFor(hotLabel, nowMs);
            if (hotQ != null) return new Plan(Kind.ASK, hotQ, null, prefaceFor(hotLabel));
            if (turnsSinceAsk >= ASK_EVERY_TURNS) {
                Question q = nextUnasked(nowMs);
                if (q != null) return new Plan(Kind.ASK, q, null, null);
            }
        }
        // 질문할 게 없으면 가끔 솔라가 먼저 자기 이야기를 한다 (채팅 읽기만 반복하지 않게)
        if (++turnsSinceStory >= STORY_EVERY) {
            turnsSinceStory = 0;
            return new Plan(Kind.STORY, null, null, null);
        }
        if (groupTurn) return new Plan(Kind.GROUP, null, null, null);
        if (topic != null && nowMs < topicUntil) {
            Question q = topic;
            return new Plan(Kind.REACT, q, c -> q.bucketOf(c.text()) != null, null);
        }
        return new Plan(Kind.REACT, null, null, null);
    }

    /** 질문을 실제로 말했을 때 부른다 */
    public synchronized String startAsk(Question q, String preface, long nowMs) {
        active = q;
        askedAt2.put(q.id(), nowMs);
        turnsSinceStory = 0;
        askedAt = nowMs;
        lastAskAt = nowMs;
        turnsSinceAsk = 0;
        topic = q;
        topicUntil = Long.MAX_VALUE;
        return (preface == null ? "" : preface) + q.ask();
    }

    /**
     * 질문 뒤에 온 채팅에서 답을 센다. 한 사람은 마지막 답 하나만.
     * @param voiceCalled 이번 라이브에서 이미 목소리로 불린 팬 — 하이라이트는 아직 안 불린 팬 중에서 고른다 (공정)
     */
    public synchronized Answers finishAsk(List<Chat> recent, Set<String> voiceCalled, long nowMs) {
        Question q = active;
        Map<String, String> lastBucket = new LinkedHashMap<>();
        Map<String, String> nick = new LinkedHashMap<>();
        for (Chat c : recent) {
            if (c.receivedAtMs() < askedAt) continue;
            String b = q.bucketOf(c.text());
            if (b == null) continue;
            lastBucket.remove(c.viewerId());
            lastBucket.put(c.viewerId(), b);
            nick.put(c.viewerId(), c.nickname());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String b : lastBucket.values()) counts.merge(b, 1, Integer::sum);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        String highlight = null;
        for (String id : lastBucket.keySet()) {
            if (!voiceCalled.contains(id)) {
                highlight = nick.get(id);
                break;
            }
        }
        if (highlight == null && !nick.isEmpty()) highlight = nick.values().iterator().next();

        active = null;
        topicUntil = nowMs + TOPIC_MS;
        topicAskedAt = askedAt;
        talkPending = !lastBucket.isEmpty();
        return new Answers(q, counts, lastBucket.size(), List.copyOf(lastBucket.keySet()), highlight,
                answersLine(q, sorted, highlight));
    }

    static String answersLine(Question q, List<Map.Entry<String, Integer>> sorted, String highlight) {
        if (sorted.isEmpty()) return "어, 아무도 대답 안 했다… 솔라 혼자 떠든 거예요? 히히.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getKey()).append(' ').append(sorted.get(i).getValue()).append("명");
        }
        sb.append("! ");
        if (highlight != null) sb.append(highlight).append("님 답 고마워요. ");
        sb.append(q.comment());
        return sb.toString();
    }

    /**
     * 이어 말하기 재료를 고른다: 질문 뒤 답 중에서 서로 다른 답 칸 2개, 아직 목소리로 안 불린 팬 먼저 (공정).
     * @param said 이번 라이브에서 이미 이름이 나온 팬(목소리) — 되도록 피한다
     */
    public synchronized Talk talkMaterial(List<Chat> recent, Set<String> said) {
        Question q = topic;
        Map<String, Chat> byBucket = new LinkedHashMap<>();
        // 1차: 아직 안 불린 팬, 2차: 아무나 (같은 칸 중에선 가장 최근 답)
        for (int pass = 0; pass < 2 && byBucket.size() < 2; pass++) {
            for (int i = recent.size() - 1; i >= 0 && byBucket.size() < 2; i--) {
                Chat c = recent.get(i);
                if (c.receivedAtMs() < topicAskedAt) continue;
                String b = q.bucketOf(c.text());
                if (b == null || byBucket.containsKey(b)) continue;
                if (pass == 0 && said.contains(c.viewerId())) continue;
                if (byBucket.values().stream().anyMatch(x -> x.viewerId().equals(c.viewerId()))) continue;
                byBucket.put(b, c);
            }
        }
        List<Chat> chats = List.copyOf(byBucket.values());
        return new Talk(q, chats, talkTemplate(q, new ArrayList<>(byBucket.keySet()), chats, talkCount++));
    }

    /** "라면이랑" / "피자랑": 마지막 글자에 받침이 있으면 "이랑" */
    static String rang(String word) {
        char c = word.charAt(word.length() - 1);
        boolean batchim = c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
        return batchim ? "이랑" : "랑";
    }

    static String talkTemplate(Question q, List<String> buckets, List<Chat> chats) {
        return talkTemplate(q, buckets, chats, 0);
    }

    /** variant = 몇 번째 이어 말하기인지. 질문마다 틀 3개를 돌아가며 쓴다 (템플릿도 같은 틀 반복을 피한다) */
    static String talkTemplate(Question q, List<String> buckets, List<Chat> chats, int variant) {
        if (chats.isEmpty()) return "다들 답해 줘서 고마워요! 솔라 오늘 지구 공부 많이 했어요.";
        String n1 = chats.get(0).nickname(), b1 = buckets.get(0);
        if (chats.size() == 1) return n1 + "님은 " + b1 + "! " + q.comment();
        String n2 = chats.get(1).nickname(), b2 = buckets.get(1);
        int v = Math.floorMod(variant, 3);
        if (v == 1) return switch (q.id()) {
            case "dinner" -> n1 + "님 " + b1 + ", " + n2 + "님 " + b2 + "! 둘 다 맛있겠다, 다른 분들은 뭐 먹었어요?";
            case "game" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 둘이 같이 하면 재밌겠다!";
            case "mood" -> n1 + "님, " + n2 + "님 둘 다 오늘 하루 잘 버텼어요. 솔라가 박수 보냄 해요!";
            default -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 다른 분들은 어때요?";
        };
        if (v == 2) return switch (q.id()) {
            case "dinner" -> b1 + rang(b1) + " " + b2 + "! " + n1 + "님, " + n2 + "님 덕분에 솔라 지구 메뉴 공부 끝!";
            case "game" -> b1 + "파 " + n1 + "님, " + b2 + "파 " + n2 + "님! 솔라는 응원 담당 할게요.";
            case "mood" -> b1.replace(" 분", "") + " " + n1 + "님도, " + b2.replace(" 분", "") + " " + n2 + "님도 오늘은 솔라랑 같이 쉬어 가요.";
            default -> b1 + rang(b1) + " " + b2 + "! " + n1 + "님, " + n2 + "님 덕분에 솔라 지구 공부함 해요.";
        };
        return switch (q.id()) {
            case "dinner" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 솔라 별엔 음식이 없어서 다 궁금함 해요.";
            case "game" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 솔라는 구경만 해도 두근두근해요.";
            case "mood" -> n1 + "님처럼 " + b1.replace(" 분", "") + " 날도, " + n2 + "님처럼 " + b2.replace(" 분", "") + " 날도 있죠. 솔라가 옆에서 반짝일게요.";
            default -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! " + q.comment();
        };
    }

    /** 자기 이야기 한 줄 (돌아가며, 한 바퀴 돌기 전엔 같은 이야기 안 함) */
    public synchronized String storyLine() {
        return STORIES.get(storyTurn++ % STORIES.size());
    }

    public synchronized Status status(long nowMs) {
        if (inOpening(nowMs)) return new Status("방송 시작 인사", false, OPENING_MS - (nowMs - liveStartAt), null);
        if (active != null) return new Status(active.label(), true, Math.max(0, ANSWER_MS - (nowMs - askedAt)), active.ask());
        if (topic != null && nowMs < topicUntil) return new Status(topic.label(), false, topicUntil - nowMs, null);
        return new Status(null, false, 0, null);
    }

    /** 떡밥 구간(질문해서 답 모으는 중, 또는 정리 뒤 이어 말하기 차례 대기) — 이때는 후원 읽기를 보류한다 */
    public synchronized boolean inSegment() {
        return active != null || talkPending;
    }

    public synchronized Question active() {
        return active;
    }

    private boolean available(Question q, long nowMs) {
        Long t = askedAt2.get(q.id());
        return t == null || nowMs - t >= REASK_MS;
    }

    /** 지금 물을 수 있는 질문을 돌아가며 (최근 4분 안에 한 질문은 빼고). 없으면 null */
    private Question nextUnasked(long nowMs) {
        for (int i = 0; i < ROTATION.size(); i++) {
            Question q = ROTATION.get(rotation++ % ROTATION.size());
            if (available(q, nowMs)) return q;
        }
        return null;
    }

    /** 떡밥 주제에 맞는 질문 중 지금 물을 수 있는 것 (음식이면 저녁 → 야식 순) */
    Question questionFor(String hotLabel, long nowMs) {
        List<Question> candidates = LineWriter.FOOD_KW.contains(hotLabel) ? List.of(DINNER, SNACK)
                : LineWriter.GAME_KW.contains(hotLabel) ? List.of(GAME, WEEKEND)
                : hotLabel.equals("피곤") ? List.of(MOOD, WEEKEND) : List.of(DINNER);
        for (Question q : candidates) if (available(q, nowMs)) return q;
        return null;
    }

    private static String hottest(Map<String, Integer> hot) {
        String best = null;
        for (Map.Entry<String, Integer> e : hot.entrySet()) {
            if (e.getKey().equals("인사")) continue; // 인사가 많은 건 떡밥이 아니다
            if (e.getValue() >= HOT_MIN && (best == null || e.getValue() > hot.get(best))) best = e.getKey();
        }
        return best;
    }


    static String prefaceFor(String hotLabel) {
        if (hotLabel.equals("피곤")) return "오늘 피곤한 분이 많네요. ";
        return "지금 " + hotLabel + " 얘기 많다! ";
    }

    private static Map<String, List<String>> buckets(List<String> keywords) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String k : keywords) m.put(k, List.of(k));
        return m;
    }

    private static Map<String, List<String>> orderedBuckets(String[][] rows) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String[] r : rows) {
            Set<String> kws = new LinkedHashSet<>(List.of(r).subList(1, r.length));
            m.put(r[0], List.copyOf(kws));
        }
        return m;
    }
}
