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

    public enum Kind { REACT, ASK, ANSWERS, TALK, GROUP }

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
    static final List<Question> ROTATION = List.of(DINNER, MOOD, GAME);

    private Question active;
    private long askedAt;
    private long lastAskAt = -ASK_GAP_MS;
    private int turnsSinceAsk;
    private int rotation;
    /** 이번 라이브에서 이미 던진 질문 (같은 질문을 또 하지 않게) */
    private final Set<String> asked = new java.util.HashSet<>();
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
            if (hotLabel != null && !asked.contains(questionFor(hotLabel).id())) {
                Question q = questionFor(hotLabel);
                return new Plan(Kind.ASK, q, null, prefaceFor(hotLabel));
            }
            if (turnsSinceAsk >= ASK_EVERY_TURNS) {
                Question q = nextUnasked();
                if (q != null) return new Plan(Kind.ASK, q, null, null);
            }
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
        asked.add(q.id());
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
            default -> n1 + "님, " + n2 + "님 고마워요! " + q.comment();
        };
        if (v == 2) return switch (q.id()) {
            case "dinner" -> b1 + rang(b1) + " " + b2 + "! " + n1 + "님, " + n2 + "님 덕분에 솔라 지구 메뉴 공부 끝!";
            case "game" -> b1 + "파 " + n1 + "님, " + b2 + "파 " + n2 + "님! 솔라는 응원 담당 할게요.";
            case "mood" -> b1.replace(" 분", "") + " " + n1 + "님도, " + b2.replace(" 분", "") + " " + n2 + "님도 오늘은 솔라랑 같이 쉬어 가요.";
            default -> n1 + "님, " + n2 + "님 이야기 재밌어요! " + q.comment();
        };
        return switch (q.id()) {
            case "dinner" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 솔라 별엔 음식이 없어서 다 궁금함 해요.";
            case "game" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 솔라는 구경만 해도 두근두근해요.";
            case "mood" -> n1 + "님처럼 " + b1.replace(" 분", "") + " 날도, " + n2 + "님처럼 " + b2.replace(" 분", "") + " 날도 있죠. 솔라가 옆에서 반짝일게요.";
            default -> n1 + "님, " + n2 + "님 이야기 재밌어요! " + q.comment();
        };
    }

    public synchronized Status status(long nowMs) {
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

    /** 아직 안 던진 질문을 돌아가며. 다 던졌으면 null (이 데모는 질문이 3개뿐 — 실제라면 캐릭터별 질문 목록이 늘어난다) */
    private Question nextUnasked() {
        for (int i = 0; i < ROTATION.size(); i++) {
            Question q = ROTATION.get(rotation++ % ROTATION.size());
            if (!asked.contains(q.id())) return q;
        }
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

    static Question questionFor(String hotLabel) {
        if (LineWriter.FOOD_KW.contains(hotLabel)) return DINNER;
        if (LineWriter.GAME_KW.contains(hotLabel)) return GAME;
        if (hotLabel.equals("피곤")) return MOOD;
        return DINNER;
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
