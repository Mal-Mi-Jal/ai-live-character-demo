package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 라이브 방 하나. 스프링을 모르는 순수 Java라서 시계(nowMs)를 밖에서 넣어 준다
 * → 실시간 서버와, 시계를 빨리 돌리는 오프라인 측정이 같은 코드를 쓴다.
 *
 * 흐름: receive()가 채팅을 쌓는다 → tick()이 몇 초마다 쌓인 걸 한꺼번에 꺼내
 *       시청자당 한 줄로 정리 → 규칙이 k명 고름 → 사실(주제·기억·감정) + 템플릿 대사 → 기록.
 *       LLM으로 말투를 바꾸는 일은 이 클래스 밖(잠금 밖)에서 한다.
 *
 * 동시성: 봇 스케줄러 스레드와 사람 채팅(STOMP 스레드)이 동시에 receive()를 부른다.
 * Quiet Consensus처럼 방 하나에 잠금 하나. 잠금 안에서는 빠른 일만 한다.
 */
public class LiveRoom {

    /** 아직 반응 못 받은 채팅을 공정 규칙이 다시 보는 시간 */
    public static final long CARRY_MS = 30_000;

    /**
     * 캐릭터 반응 한 건. topic·keyword·recall은 코드가 정한 사실이고, line·emotion은 템플릿 또는 LLM이 쓴 것.
     * source: "template" 또는 "llm"
     */
    public record Reaction(String viewerId, String nickname, String chatText, int gift,
                           LineWriter.Topic topic, String keyword, String recall,
                           String line, LineWriter.Emotion emotion, String source,
                           long waitMs, boolean human) {

        public Reaction withLine(String newLine, LineWriter.Emotion newEmotion, String newSource) {
            return new Reaction(viewerId, nickname, chatText, gift, topic, keyword, recall,
                    newLine, newEmotion, newSource, waitMs, human);
        }
    }

    /** 화면 인정: 목소리 대신 화면에 "솔라가 봤어요 ❤"로 이름을 띄운다 */
    public record Ack(String viewerId, String nickname, String chatText) {
    }

    /** 묶음 인정: 같은 이야기를 한 팬 여럿을 한 줄로 알아본다. 몇 명인지는 코드가 센다 */
    public record GroupMoment(String label, int count, List<String> nicknames, String line) {
    }

    /**
     * 지금까지의 숫자. reactedFans = 목소리로 이름이 불린 팬, anyAckFans = 목소리·화면·묶음 중 하나라도 받은 팬
     */
    public record Stats(String rule, int tickNo, long totalChats, int chatters, int reactedFans,
                        double reactedRatio, int anyAckFans, double anyAckRatio,
                        long waitP50Ms, long waitP95Ms, int lastBatchSize,
                        int lastCandidates, int lastPool, long blockedChats, int rememberedFans,
                        long recalls, int liveNo) {
    }

    /**
     * 끝난 라이브 한 번의 요약 (전부 코드가 센 숫자). openingLine = 다음 라이브 첫 대사.
     */
    public record LiveSummary(int liveNo, int chatters, int voiceFans, int anyAckFans,
                              String topLabel, int topCount, String openingLine) {
    }

    /**
     * 다음 라이브 복귀율: 지난 라이브에서 채팅한 팬 중 이번 라이브에도 채팅한 비율을
     * "지난번에 인정(목소리·화면·묶음) 받은 팬" / "못 받은 팬" / "목소리로 불린 팬"으로 나눠 본다.
     */
    public record ReturnStats(int prevLiveNo, int prevAcked, int returnedAcked, int prevNotAcked,
                              int returnedNotAcked, int prevVoice, int returnedVoice) {
        public double ackedRate() { return prevAcked == 0 ? 0 : (double) returnedAcked / prevAcked; }
        public double notAckedRate() { return prevNotAcked == 0 ? 0 : (double) returnedNotAcked / prevNotAcked; }
        public double voiceRate() { return prevVoice == 0 ? 0 : (double) returnedVoice / prevVoice; }
    }

    public record TickResult(int tickNo, List<Reaction> reactions, List<Ack> screenAcks, Stats stats) {
    }

    /** 묶음 인정은 이 인원 이상일 때만 (2명이면 "분들"이 어색하고, 그냥 이름을 부르는 게 낫다) */
    public static final int GROUP_MIN = 3;

    private final ReentrantLock lock = new ReentrantLock();
    private final LineWriter writer = new LineWriter();
    private final FanMemory memory = new FanMemory();
    private final int k;
    private final int screenK;
    private final long voiceMaxAgeMs;
    private final Random rnd;

    private PickRule rule;
    private FanLedger ledger = new FanLedger();
    /** 다음 틱에 캐릭터가 볼 채팅 */
    private List<Chat> pending = new ArrayList<>();
    /** 아직 반응 못 받은 최근 채팅 (시청자당 한 줄) */
    private Map<String, Chat> unanswered = new LinkedHashMap<>();
    /** 다음 채팅창 전송 때 내보낼 채팅 */
    private List<Chat> unflushed = new ArrayList<>();
    private long seq;
    private long totalChats;
    private long blockedChats;
    private long recalls;
    private long groupMoments;
    // ---- 라이브 회차 ----
    private int liveNo = 1;
    /** 이번 라이브에서 주제별로 말한 팬 (요약·오프닝용) */
    private Map<String, java.util.Set<String>> topicFans = new LinkedHashMap<>();
    /** 지난 라이브에서 채팅한 / 인정 받은 / 목소리로 불린 팬 */
    private java.util.Set<String> prevChatters = java.util.Set.of();
    private java.util.Set<String> prevAcked = java.util.Set.of();
    private java.util.Set<String> prevVoice = java.util.Set.of();
    /** 이번 라이브에서 "또 와 줬네요"를 이미 말한 팬 (한 라이브에 한 번만) */
    private java.util.Set<String> greetedReturning = new java.util.HashSet<>();
    private int tickNo;
    private int lastBatchSize;
    private int lastCandidates;
    private int lastPool;

    public LiveRoom(PickRule rule, int k, long seed) {
        this(rule, k, 0, CARRY_MS, seed);
    }

    /**
     * @param screenK        목소리 k명 말고, 같은 규칙으로 화면에 이름만 띄울 인원
     * @param voiceMaxAgeMs  목소리로 답할 채팅의 최대 나이. 이보다 오래된 채팅은 화면·묶음 인정 후보로만 남는다
     */
    public LiveRoom(PickRule rule, int k, int screenK, long voiceMaxAgeMs, long seed) {
        this.rule = rule;
        this.k = k;
        this.screenK = screenK;
        this.voiceMaxAgeMs = voiceMaxAgeMs;
        this.rnd = new Random(seed);
    }

    /** 채팅 하나를 받는다. 금칙어가 있으면 채팅창에는 가려서 보이되 반응 후보·기억에서는 빠진다 */
    public Chat receive(String viewerId, String nickname, String text, int gift, long nowMs, boolean human) {
        SafetyFilter.Result safe = SafetyFilter.check(text);
        String nick = SafetyFilter.check(nickname).text();
        lock.lock();
        try {
            Chat c = new Chat(++seq, viewerId, nick, safe.text(), Math.max(0, gift), nowMs, human);
            totalChats++;
            unflushed.add(c);
            if (safe.blocked()) {
                blockedChats++;
            } else {
                pending.add(c);
                ledger.onChat(c);
                LineWriter.Detected d = LineWriter.detect(c.text(), c.gift());
                if (d.memorable()) memory.remember(viewerId, d.keyword(), nowMs);
                String label = groupLabel(d);
                if (label != null) topicFans.computeIfAbsent(label, x -> new java.util.HashSet<>()).add(viewerId);
            }
            return c;
        } finally {
            lock.unlock();
        }
    }

    /** 채팅창에 보낼 새 채팅을 꺼낸다 (꺼낸 건 비워진다) */
    public List<Chat> takeUnflushed() {
        lock.lock();
        try {
            List<Chat> out = unflushed;
            unflushed = new ArrayList<>();
            return out;
        } finally {
            lock.unlock();
        }
    }

    public TickResult tick(long nowMs) {
        lock.lock();
        try {
            tickNo++;
            List<Chat> batch = pending;
            pending = new ArrayList<>();
            lastBatchSize = batch.size();

            List<Chat> fresh = onePerViewer(batch);
            lastCandidates = fresh.size();

            // 못 받은 채팅 목록 갱신: 새로 말했으면 최신 채팅으로 바꾸고 선물은 합친다. 오래된 건 버린다
            for (Chat c : fresh) {
                Chat old = unanswered.remove(c.viewerId());
                unanswered.put(c.viewerId(), old == null ? c : withGift(c, c.gift() + old.gift()));
            }
            for (Iterator<Chat> it = unanswered.values().iterator(); it.hasNext(); ) {
                if (nowMs - it.next().receivedAtMs() > CARRY_MS) it.remove();
            }
            List<Chat> pool = List.copyOf(unanswered.values());
            lastPool = pool.size();

            // 목소리는 최근 voiceMaxAgeMs 안의 채팅에만 답한다 (오래된 채팅을 뜬금없이 읽지 않게).
            // 그보다 오래 기다린 채팅은 화면 ❤·묶음 인정으로 챙긴다
            List<Chat> voicePool = new ArrayList<>();
            for (Chat c : pool) if (nowMs - c.receivedAtMs() <= voiceMaxAgeMs) voicePool.add(c);
            List<Chat> voiceFresh = new ArrayList<>();
            for (Chat c : fresh) if (nowMs - c.receivedAtMs() <= voiceMaxAgeMs) voiceFresh.add(c);
            List<Chat> picked = rule.pick(new PickRule.Context(voiceFresh, voicePool, ledger, k, rnd, nowMs));

            // 목소리로 고른 사람은 화면 후보에서 뺀다 (선물을 합친 복사본일 수 있어서 id로 비교)
            java.util.Set<String> spoken = new java.util.HashSet<>();
            for (Chat c : picked) spoken.add(c.viewerId());
            List<Chat> restFresh = new ArrayList<>();
            for (Chat c : fresh) if (!spoken.contains(c.viewerId())) restFresh.add(c);
            List<Chat> restPool = new ArrayList<>();
            for (Chat c : pool) if (!spoken.contains(c.viewerId())) restPool.add(c);
            List<Chat> screen = screenK == 0 ? List.of()
                    : rule.pick(new PickRule.Context(restFresh, restPool, ledger, screenK, rnd, nowMs));
            List<Ack> acks = new ArrayList<>();
            for (Chat c : screen) {
                unanswered.remove(c.viewerId());
                ledger.onScreenAck(c.viewerId());
                acks.add(new Ack(c.viewerId(), c.nickname(), c.text()));
            }
            List<Reaction> reactions = new ArrayList<>();
            for (Chat c : picked) {
                unanswered.remove(c.viewerId());
                ledger.onReacted(c, nowMs);
                LineWriter.Detected d = LineWriter.detect(c.text(), c.gift());
                String recall = memory.recall(c.viewerId(), d.keyword(), nowMs);
                LineWriter.Line line;
                boolean generic = d.topic() == LineWriter.Topic.GREETING || d.topic() == LineWriter.Topic.OTHER;
                if (recall == null && generic && prevChatters.contains(c.viewerId()) && greetedReturning.add(c.viewerId())) {
                    // 지난 라이브에도 왔던 팬 (꺼낼 키워드는 없지만 다시 온 것 자체를 알아본다).
                    // 인사·잡담일 때만 — 구체적인 말을 했으면 그 말에 답하는 게 먼저다
                    recall = RETURNING;
                    line = new LineWriter.Line(RETURNING_LINES.get(rnd.nextInt(RETURNING_LINES.size()))
                            .replace("{nick}", c.nickname()), LineWriter.Emotion.TOUCHED);
                } else {
                    line = writer.write(c.nickname(), d, recall, rnd);
                }
                if (recall != null) recalls++;
                reactions.add(new Reaction(c.viewerId(), c.nickname(), c.text(), c.gift(),
                        d.topic(), d.keyword(), recall, line.text(), line.emotion(), "template",
                        nowMs - c.receivedAtMs(), c.human()));
            }
            return new TickResult(tickNo, List.copyOf(reactions), List.copyOf(acks), statsLocked());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 묶음 인정 하나. 쌓인 채팅(답 못 받은 최근 30초)을 주제별로 모아, 가장 많은 주제가 GROUP_MIN명 이상이면
     * "라면 얘기한 5명, 다 봤어요!" 한 줄로 그 팬들을 함께 알아본다. 없으면 null (그 차례는 평소처럼 한 명).
     * 문장은 템플릿만 쓴다 — 인원 숫자가 들어가서, LLM에게 맡기면 숫자 검사가 더 필요하다.
     */
    public GroupMoment groupMoment(long nowMs) {
        lock.lock();
        try {
            // 이번 차례 전까지 쌓인 채팅도 함께 본다
            for (Chat c : onePerViewer(pending)) {
                Chat old = unanswered.remove(c.viewerId());
                unanswered.put(c.viewerId(), old == null ? c : withGift(c, c.gift() + old.gift()));
            }
            pending = new ArrayList<>();
            Map<String, List<Chat>> byLabel = new LinkedHashMap<>();
            for (Chat c : unanswered.values()) {
                if (nowMs - c.receivedAtMs() > CARRY_MS) continue;
                String label = groupLabel(LineWriter.detect(c.text(), c.gift()));
                if (label != null) byLabel.computeIfAbsent(label, x -> new ArrayList<>()).add(c);
            }
            String best = null;
            for (Map.Entry<String, List<Chat>> e : byLabel.entrySet()) {
                if (best == null || e.getValue().size() > byLabel.get(best).size()) best = e.getKey();
            }
            if (best == null || byLabel.get(best).size() < GROUP_MIN) return null;
            List<Chat> members = byLabel.get(best);
            List<String> names = new ArrayList<>();
            for (Chat c : members) {
                unanswered.remove(c.viewerId());
                ledger.onGroupAck(c.viewerId());
                names.add(c.nickname());
            }
            groupMoments++;
            return new GroupMoment(best, members.size(), List.copyOf(names), groupLine(best, members.size()));
        } finally {
            lock.unlock();
        }
    }

    private static String groupLabel(LineWriter.Detected d) {
        return switch (d.topic()) {
            case FOOD, GAME -> d.keyword();
            case TIRED -> "피곤";
            case GREETING -> "인사";
            default -> null;
        };
    }

    static String groupLine(String label, int n) {
        return switch (label) {
            case "피곤" -> "오늘 지친 " + n + "명, 다 같이 쉬어 가요!";
            case "인사" -> "인사해 준 " + n + "명, 다들 반가워요!";
            default -> label + " 얘기한 " + n + "명, 솔라 다 봤어요!";
        };
    }

    /**
     * 시청자당 한 줄: 같은 사람의 마지막 채팅만 남긴다 (도배해도 확률이 오르지 않게).
     * 선물은 그 틱 안에서 합쳐 둔다 — 선물 뒤에 일반 채팅을 쳤다고 선물이 사라지면 안 되니까
     */
    private static List<Chat> onePerViewer(List<Chat> batch) {
        Map<String, Chat> last = new LinkedHashMap<>();
        Map<String, Integer> gifts = new LinkedHashMap<>();
        for (Chat c : batch) {
            last.remove(c.viewerId()); // 순서를 "마지막으로 말한 순"으로 유지
            last.put(c.viewerId(), c);
            gifts.merge(c.viewerId(), c.gift(), Integer::sum);
        }
        List<Chat> out = new ArrayList<>();
        for (Chat c : last.values()) {
            int g = gifts.get(c.viewerId());
            out.add(g == c.gift() ? c : withGift(c, g));
        }
        return out;
    }

    private static Chat withGift(Chat c, int gift) {
        return new Chat(c.seq(), c.viewerId(), c.nickname(), c.text(), gift, c.receivedAtMs(), c.human());
    }

    /** 규칙을 바꾸면 숫자를 처음부터 다시 센다 (규칙끼리 같은 조건에서 비교하려고). 팬 기억은 남는다 */
    /** 키워드 없이 "지난 라이브에도 왔던 팬"을 알아볼 때 recall 칸에 넣는 값 */
    public static final String RETURNING = "지난 라이브";
    private static final List<String> RETURNING_LINES = List.of(
            "{nick}님 또 와 줬네요! 진짜 반가워요!",
            "{nick}님 지난 라이브에도 왔죠? 솔라 기억함 해요!",
            "{nick}님 다시 만났다! 꼬리별 흔들흔들!");

    /**
     * 지금 라이브를 끝내고 다음 라이브를 시작한다.
     * 숫자(팬별 기록)는 새로 세고, 팬 기억(FanMemory)은 그대로 남는다 = 관계가 다음 라이브로 이어진다.
     * @return 끝난 라이브의 요약 (openingLine = 다음 라이브 첫 대사)
     */
    public LiveSummary startNextLive() {
        lock.lock();
        try {
            java.util.Set<String> chatters = new java.util.HashSet<>(), acked = new java.util.HashSet<>(), voice = new java.util.HashSet<>();
            for (FanLedger.Fan f : ledger.all()) {
                chatters.add(f.viewerId);
                if (f.reactedCount + f.screenAcked + f.groupAcked > 0) acked.add(f.viewerId);
                if (f.reactedCount > 0) voice.add(f.viewerId);
            }
            String top = null;
            for (Map.Entry<String, java.util.Set<String>> e : topicFans.entrySet()) {
                if (top == null || e.getValue().size() > topicFans.get(top).size()) top = e.getKey();
            }
            int topCount = top == null ? 0 : topicFans.get(top).size();
            LiveSummary summary = new LiveSummary(liveNo, chatters.size(), voice.size(), acked.size(),
                    top, topCount, openingLine(top, topCount));

            prevChatters = java.util.Set.copyOf(chatters);
            prevAcked = java.util.Set.copyOf(acked);
            prevVoice = java.util.Set.copyOf(voice);
            liveNo++;
            topicFans = new LinkedHashMap<>();
            greetedReturning = new java.util.HashSet<>();
            resetCounters();
            return summary;
        } finally {
            lock.unlock();
        }
    }

    static String openingLine(String top, int count) {
        if (top == null) return "다시 만나서 반가워요! 솔라 오늘도 반짝!";
        return switch (top) {
            case "피곤" -> "지난 라이브엔 지친 분이 " + count + "명이었죠. 오늘은 푹 쉬어 가요!";
            case "인사" -> "지난번에 인사해 준 " + count + "명! 오늘도 안녕안녕!";
            default -> "지난 라이브엔 " + top + " 얘기한 분이 " + count + "명이었죠! 오늘도 반가워요!";
        };
    }

    /** 지난 라이브 팬 중 이번 라이브에 다시 채팅한 비율 (첫 라이브면 null) */
    public ReturnStats returnStats() {
        lock.lock();
        try {
            if (liveNo == 1) return null;
            int ra = 0, rn = 0, rv = 0;
            for (String id : prevChatters) {
                if (ledger.get(id) == null) continue;
                if (prevAcked.contains(id)) ra++;
                else rn++;
                if (prevVoice.contains(id)) rv++;
            }
            return new ReturnStats(liveNo - 1, prevAcked.size(), ra, prevChatters.size() - prevAcked.size(), rn,
                    prevVoice.size(), rv);
        } finally {
            lock.unlock();
        }
    }

    public void reset(PickRule newRule) {
        lock.lock();
        try {
            rule = newRule;
            resetCounters();
        } finally {
            lock.unlock();
        }
    }

    /** 잠금 안에서만 부른다 */
    private void resetCounters() {
        ledger = new FanLedger();
        pending = new ArrayList<>();
        unanswered = new LinkedHashMap<>();
        totalChats = 0;
        blockedChats = 0;
        recalls = 0;
        groupMoments = 0;
        tickNo = 0;
        lastBatchSize = 0;
        lastCandidates = 0;
        lastPool = 0;
    }

    public Stats stats() {
        lock.lock();
        try {
            return statsLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 숫자 계산용으로 팬별 기록을 읽는다 (시뮬레이션·테스트 전용, 잠금 안에서 복사해 돌려준다) */
    public List<FanLedger.Fan> fansSnapshot() {
        lock.lock();
        try {
            return List.copyOf(ledger.all());
        } finally {
            lock.unlock();
        }
    }

    private Stats statsLocked() {
        int chatters = ledger.chatters();
        int reacted = ledger.reactedFans();
        int any = ledger.anyAckFans();
        return new Stats(rule.id(), tickNo, totalChats, chatters, reacted,
                chatters == 0 ? 0 : (double) reacted / chatters,
                any, chatters == 0 ? 0 : (double) any / chatters,
                ledger.waitPercentile(0.5), ledger.waitPercentile(0.95),
                lastBatchSize, lastCandidates, lastPool, blockedChats, memory.fans(), recalls, liveNo);
    }
}
