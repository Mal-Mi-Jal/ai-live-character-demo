package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 라이브 방 하나. 스프링을 모르는 순수 Java라서 시계(nowMs)를 밖에서 넣어 준다
 * → 실시간 서버와, 시계를 빨리 돌리는 오프라인 측정(3일차)이 같은 코드를 쓴다.
 *
 * 흐름: receive()가 채팅을 쌓는다 → tick()이 몇 초마다 쌓인 걸 한꺼번에 꺼내
 *       시청자당 한 줄로 정리 → 규칙이 k명 고름 → 대사 생성 → 기록.
 *
 * 동시성: 봇 스케줄러 스레드와 사람 채팅(STOMP 스레드)이 동시에 receive()를 부른다.
 * Quiet Consensus처럼 방 하나에 잠금 하나. 잠금 안에서는 빠른 일만 한다 (LLM을 붙이면 잠금 밖에서).
 */
public class LiveRoom {

    /** 캐릭터 반응 한 건 */
    public record Reaction(String viewerId, String nickname, String chatText, int gift, String line,
                           long waitMs, boolean human) {
    }

    /** 지금까지의 숫자 */
    public record Stats(String rule, int tickNo, long totalChats, int chatters, int reactedFans,
                        double reactedRatio, long waitP50Ms, long waitP95Ms, int lastBatchSize,
                        int lastCandidates, long blockedChats) {
    }

    public record TickResult(int tickNo, List<Reaction> reactions, Stats stats) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final LineWriter writer = new LineWriter();
    private final int k;
    private final Random rnd;

    private PickRule rule;
    private FanLedger ledger = new FanLedger();
    /** 다음 틱에 캐릭터가 볼 채팅 */
    private List<Chat> pending = new ArrayList<>();
    /** 다음 채팅창 전송 때 내보낼 채팅 */
    private List<Chat> unflushed = new ArrayList<>();
    private long seq;
    private long totalChats;
    private long blockedChats;
    private int tickNo;
    private int lastBatchSize;
    private int lastCandidates;

    public LiveRoom(PickRule rule, int k, long seed) {
        this.rule = rule;
        this.k = k;
        this.rnd = new Random(seed);
    }

    /** 채팅 하나를 받는다. 금칙어가 있으면 채팅창에는 가려서 보이되 반응 후보에서는 빠진다 */
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

            // 시청자당 한 줄: 같은 사람의 마지막 채팅만 남긴다 (도배해도 확률이 오르지 않게).
            // 선물은 그 틱 안에서 합쳐 둔다 — 선물 뒤에 일반 채팅을 쳤다고 선물이 사라지면 안 되니까
            Map<String, Chat> lastPerViewer = new LinkedHashMap<>();
            Map<String, Integer> giftPerViewer = new LinkedHashMap<>();
            for (Chat c : batch) {
                lastPerViewer.remove(c.viewerId()); // 순서를 "마지막으로 말한 순"으로 유지
                lastPerViewer.put(c.viewerId(), c);
                giftPerViewer.merge(c.viewerId(), c.gift(), Integer::sum);
            }
            List<Chat> candidates = new ArrayList<>();
            for (Chat c : lastPerViewer.values()) {
                int g = giftPerViewer.get(c.viewerId());
                candidates.add(g == c.gift() ? c
                        : new Chat(c.seq(), c.viewerId(), c.nickname(), c.text(), g, c.receivedAtMs(), c.human()));
            }
            lastCandidates = candidates.size();

            List<Chat> picked = rule.pick(candidates, ledger, k, rnd);
            List<Reaction> reactions = new ArrayList<>();
            for (Chat c : picked) {
                // 대기 시간은 "그 사람이 이번 틱에 처음 말한 때"가 아니라 고른 그 채팅 기준
                ledger.onReacted(c, nowMs);
                reactions.add(new Reaction(c.viewerId(), c.nickname(), c.text(), c.gift(),
                        writer.write(c, rnd), nowMs - c.receivedAtMs(), c.human()));
            }
            return new TickResult(tickNo, List.copyOf(reactions), statsLocked());
        } finally {
            lock.unlock();
        }
    }

    /** 규칙을 바꾸면 숫자도 처음부터 다시 센다 (규칙끼리 같은 조건에서 비교하려고) */
    public void reset(PickRule newRule) {
        lock.lock();
        try {
            rule = newRule;
            ledger = new FanLedger();
            pending = new ArrayList<>();
            totalChats = 0;
            blockedChats = 0;
            tickNo = 0;
            lastBatchSize = 0;
            lastCandidates = 0;
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

    private Stats statsLocked() {
        int chatters = ledger.chatters();
        int reacted = ledger.reactedFans();
        return new Stats(rule.id(), tickNo, totalChats, chatters, reacted,
                chatters == 0 ? 0 : (double) reacted / chatters,
                ledger.waitPercentile(0.5), ledger.waitPercentile(0.95),
                lastBatchSize, lastCandidates, blockedChats);
    }
}
