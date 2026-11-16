package com.livecharacter.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 후원 대기열. 후원은 들어온 순서대로 한 개씩 읽는다 (후원 메시지는 시스템 목소리, 감사 인사는 캐릭터).
 *
 * 진행을 방해하지 않게:
 *  - 진행자가 떡밥 구간(질문 → 답 모으기 → 이어 말하기) 중이면 읽지 않고 보류 (화면 알림은 바로 뜬다)
 *  - 쌓이면(BATCH_AT개 이상) 또는 오래 기다리면(MAX_WAIT_MS) 메시지는 건너뛰고 한 줄로 묶어서 감사
 * 감사 인사는 템플릿만 쓴다 (금액 숫자가 들어가서 LLM에 맡기지 않는다). 순수 Java, 시계는 밖에서 받는다.
 */
public class DonationQueue {

    public record Donation(String viewerId, String nickname, int amount, String message, boolean human,
                           long receivedAtMs) {
    }

    public enum Kind { NONE, READ, BATCH }

    /**
     * @param readText 시스템 목소리로 읽을 후원 알림 (묶음이면 null)
     * @param thanks   캐릭터가 하는 감사 인사
     */
    public record Plan(Kind kind, List<Donation> items, String readText, String thanks) {
    }

    public record Stats(int queued, long offered, long read, long batchedTurns, long batchedDonations,
                        long waitP50Ms, long waitMaxMs) {
    }

    public static final int BATCH_AT = 6;
    public static final long MAX_WAIT_MS = 60_000;
    public static final int MESSAGE_MAX = 40;

    private static final List<String> THANKS = List.of(
            "{nick}님 {amt} 선물 감사합니다~",
            "와, {nick}님 {amt}! 고마워요~",
            "{nick}님 {amt} 후원 감사함 해요!");
    private static final String BIG_EXTRA = " 꼬리별이 막 흔들려요!";

    private final ArrayDeque<Donation> queue = new ArrayDeque<>();
    private final List<Long> waits = new ArrayList<>();
    private long offered, read, batchedTurns, batchedDonations;
    private int thanksTurn;

    /** 후원을 받는다. 메시지는 개인정보를 가리고, 금칙어가 있으면 메시지는 읽지 않는다(감사 인사만) */
    public synchronized Donation offer(String viewerId, String nickname, int amount, String rawMessage,
                                       boolean human, long nowMs) {
        SafetyFilter.Result safe = SafetyFilter.check(rawMessage);
        String msg = safe.blocked() || safe.text().isBlank() ? null : safe.text();
        if (msg != null && msg.length() > MESSAGE_MAX) msg = msg.substring(0, MESSAGE_MAX);
        Donation d = new Donation(viewerId, SafetyFilter.check(nickname).text(), amount, msg, human, nowMs);
        queue.addLast(d);
        offered++;
        return d;
    }

    /**
     * 다음 차례에 후원을 읽을지.
     * @param busy 진행자가 떡밥 구간 중인가 — 그러면 쌓이거나 오래 기다린 경우만 짧게 묶어서 감사
     */
    public synchronized Plan next(long nowMs, boolean busy) {
        return next(nowMs, busy, true);
    }

    /**
     * @param readAllowed false면 이번 차례엔 하나씩 읽기는 하지 않는다 (예: 바로 앞 차례도 후원이었음 — 일반 채팅과 번갈아).
     *                    쌓인 경우의 묶음 감사는 그래도 한다.
     *                    판단을 큐 안에서 하는 이유: 꺼낸 뒤에 "이번엔 안 읽기"로 정하면 꺼낸 후원이 사라진다
     *                    (2026-11-17 화면 확인 중 실제로 한 건이 사라지는 버그를 찾음)
     */
    public synchronized Plan next(long nowMs, boolean busy, boolean readAllowed) {
        if (queue.isEmpty()) return new Plan(Kind.NONE, List.of(), null, null);
        boolean overflow = queue.size() >= BATCH_AT || nowMs - queue.peekFirst().receivedAtMs() > MAX_WAIT_MS;
        if ((busy || !readAllowed) && !overflow) return new Plan(Kind.NONE, List.of(), null, null);
        if (overflow) {
            List<Donation> all = new ArrayList<>(queue);
            queue.clear();
            all.forEach(d -> waits.add(nowMs - d.receivedAtMs()));
            batchedTurns++;
            batchedDonations += all.size();
            return new Plan(Kind.BATCH, List.copyOf(all), null, batchLine(all));
        }
        Donation d = queue.pollFirst();
        waits.add(nowMs - d.receivedAtMs());
        read++;
        return new Plan(Kind.READ, List.of(d), readText(d), thanks(d));
    }

    static String amount(int won) {
        return String.format("%,d원", won);
    }

    static String readText(Donation d) {
        String head = d.nickname() + "님 " + amount(d.amount()) + " 후원";
        return d.message() == null ? head : head + ". " + d.message();
    }

    private String thanks(Donation d) {
        String t = THANKS.get(thanksTurn++ % THANKS.size())
                .replace("{nick}", d.nickname()).replace("{amt}", amount(d.amount()));
        return d.amount() >= 10_000 ? t + BIG_EXTRA : t;
    }

    static String batchLine(List<Donation> all) {
        if (all.size() == 1) return all.get(0).nickname() + "님 후원 감사합니다!";
        String names = all.get(0).nickname() + "님, " + all.get(1).nickname() + "님";
        int rest = all.size() - 2;
        return rest > 0 ? names + " 외 " + rest + "분 후원 감사합니다!" : names + " 후원 감사합니다!";
    }

    public synchronized List<Donation> snapshot() {
        return List.copyOf(queue);
    }

    public synchronized Stats stats() {
        List<Long> s = new ArrayList<>(waits);
        Collections.sort(s);
        return new Stats(queue.size(), offered, read, batchedTurns, batchedDonations,
                s.isEmpty() ? 0 : s.get(s.size() / 2), s.isEmpty() ? 0 : s.get(s.size() - 1));
    }
}
