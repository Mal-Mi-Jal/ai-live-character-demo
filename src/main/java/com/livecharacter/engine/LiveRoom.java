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

    /** 지금까지의 숫자 */
    public record Stats(String rule, int tickNo, long totalChats, int chatters, int reactedFans,
                        double reactedRatio, long waitP50Ms, long waitP95Ms, int lastBatchSize,
                        int lastCandidates, int lastPool, long blockedChats, int rememberedFans,
                        long recalls) {
    }

    public record TickResult(int tickNo, List<Reaction> reactions, Stats stats) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final LineWriter writer = new LineWriter();
    private final FanMemory memory = new FanMemory();
    private final int k;
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
    private int tickNo;
    private int lastBatchSize;
    private int lastCandidates;
    private int lastPool;

    public LiveRoom(PickRule rule, int k, long seed) {
        this.rule = rule;
        this.k = k;
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

            List<Chat> picked = rule.pick(new PickRule.Context(fresh, pool, ledger, k, rnd, nowMs));
            List<Reaction> reactions = new ArrayList<>();
            for (Chat c : picked) {
                unanswered.remove(c.viewerId());
                ledger.onReacted(c, nowMs);
                LineWriter.Detected d = LineWriter.detect(c.text(), c.gift());
                String recall = memory.recall(c.viewerId(), d.keyword(), nowMs);
                if (recall != null) recalls++;
                LineWriter.Line line = writer.write(c.nickname(), d, recall, rnd);
                reactions.add(new Reaction(c.viewerId(), c.nickname(), c.text(), c.gift(),
                        d.topic(), d.keyword(), recall, line.text(), line.emotion(), "template",
                        nowMs - c.receivedAtMs(), c.human()));
            }
            return new TickResult(tickNo, List.copyOf(reactions), statsLocked());
        } finally {
            lock.unlock();
        }
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
    public void reset(PickRule newRule) {
        lock.lock();
        try {
            rule = newRule;
            ledger = new FanLedger();
            pending = new ArrayList<>();
            unanswered = new LinkedHashMap<>();
            totalChats = 0;
            blockedChats = 0;
            recalls = 0;
            tickNo = 0;
            lastBatchSize = 0;
            lastCandidates = 0;
            lastPool = 0;
        } finally {
            lock.unlock();
        }
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
        return new Stats(rule.id(), tickNo, totalChats, chatters, reacted,
                chatters == 0 ? 0 : (double) reacted / chatters,
                ledger.waitPercentile(0.5), ledger.waitPercentile(0.95),
                lastBatchSize, lastCandidates, lastPool, blockedChats, memory.fans(), recalls);
    }
}
