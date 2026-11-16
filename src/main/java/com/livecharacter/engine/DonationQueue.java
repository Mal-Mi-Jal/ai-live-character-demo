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

    /**
     * READ = 하나씩 읽기, BATCH = 쌓여서 묶어 감사, QUICK = 떡밥 진행 중에 큰 후원만 이름·금액으로 빠르게 감사
     */
    public enum Kind { NONE, READ, BATCH, QUICK }

    /**
     * @param readText 시스템 목소리로 읽을 후원 알림 (묶음이면 null)
     * @param thanks   캐릭터가 하는 감사 인사
     */
    public record Plan(Kind kind, List<Donation> items, String readText, String thanks) {
    }

    public record Stats(int queued, long offered, long read, long batchedTurns, long batchedDonations,
                        long waitP50Ms, long waitMaxMs, long quickTurns, long quickDonations) {
    }

    public static final int BATCH_AT = 6;
    /**
     * 이 금액 이상은 "읽어 달라"는 뜻이 담긴 큰 후원으로 보고, 어떤 경우에도 이름·금액을 부른다
     * (묶음 감사에서도 "외 N분"으로 뭉개지 않는다). 사용자 의견(2026-11-17) — 기준 금액·규칙은 기획이 정할 값
     */
    public static final int BIG_AT = 10_000;
    public static final long MAX_WAIT_MS = 60_000;
    public static final int MESSAGE_MAX = 40;

    private static final List<String> THANKS = List.of(
            "{nick}님 {amt} 선물 감사합니다~",
            "와, {nick}님 {amt}! 고마워요~",
            "{nick}님 {amt} 후원 감사함 해요!");
    private static final String BIG_EXTRA = " 꼬리별이 막 흔들려요!";

    private final ArrayDeque<Donation> queue = new ArrayDeque<>();
    private final List<Long> waits = new ArrayList<>();
    private long offered, read, batchedTurns, batchedDonations, quickTurns, quickDonations;
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
        if (busy && !overflow && readAllowed) {
            // 떡밥 진행 중: 작은 후원은 기다리게 하고, 큰 후원은 메시지 없이 이름·금액만 빠르게 감사하고 진행을 이어 간다
            List<Donation> big = queue.stream().filter(x -> x.amount() >= BIG_AT).toList();
            if (!big.isEmpty()) {
                queue.removeAll(big);
                big.forEach(x -> waits.add(nowMs - x.receivedAtMs()));
                quickTurns++;
                quickDonations += big.size();
                return new Plan(Kind.QUICK, big, null, quickLine(big));
            }
        }
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
        if (d.amount() >= 10_000) t += BIG_EXTRA;
        String reply = reply(d.message(), thanksTurn);
        return reply == null ? t : t + " " + reply;
    }

    private static final List<String> PRAISE = List.of("재밌", "재미있", "좋아", "최고", "귀여", "사랑", "응원", "힘내");

    /**
     * 후원 메시지에 짧게 한 마디 (템플릿). 무엇에 답할지는 LineWriter의 주제 판단과 칭찬 단어로 코드가 정한다.
     * 메시지가 없거나 금칙어로 안 읽은 경우는 답하지 않는다. 사용자 피드백(2026-11-17): 감사만 하면 정적이다
     */
    static String reply(String message, int turn) {
        if (message == null || message.isBlank()) return null;
        int v = Math.floorMod(turn, 2);
        for (String w : PRAISE) {
            if (message.contains(w)) return v == 0 ? "그 말 들으니까 꼬리별이 막 흔들려요!" : "솔라 진짜 힘남 해요!";
        }
        LineWriter.Detected d = LineWriter.detect(message, 0);
        String kw = d.keyword();
        return switch (d.topic()) {
            case FOOD -> v == 0 ? kw + " 맛있었어요? 부러움!" : kw + "! 솔라도 언젠가 먹어 볼래요.";
            case GAME -> v == 0 ? kw + " 이기면 자랑하러 와요!" : kw + " 같이 구경하고 싶음 해요!";
            case TIRED -> v == 0 ? "오늘 푹 쉬어요. 토닥토닥." : "피곤할 땐 쉬어 가요, 솔라가 지켜 줄게요.";
            case GREETING -> v == 0 ? "와 줘서 반가워요!" : "솔라도 반가움 많이 해요!";
            case QUESTION -> v == 0 ? "그 질문은… 비밀이에요, 히히." : "음, 솔라 별 머리로 생각해 볼게요!";
            default -> v == 0 ? "메시지까지 고마워요!" : "그 말 오래 기억할게요!";
        };
    }

    /** 큰 후원 이름 부르기: 금액이 같으면 "A님, B님 10,000원 후원 감사합니다~", 다르면 사람마다 금액 */
    static String bigNames(List<Donation> big) {
        boolean same = big.stream().map(Donation::amount).distinct().count() == 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < big.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(big.get(i).nickname()).append("님");
            if (!same) sb.append(' ').append(amount(big.get(i).amount()));
        }
        if (same) sb.append(' ').append(amount(big.get(0).amount()));
        return sb.toString();
    }

    static String quickLine(List<Donation> big) {
        // 메시지는 읽지 않는다 ("이따 볼게요"라고 약속하지 않는다 — 실제로 다시 읽는 기능은 없으니까)
        return bigNames(big) + " 후원 감사합니다~ 하던 이야기 이어 갈게요!";
    }

    static String batchLine(List<Donation> all) {
        List<Donation> big = all.stream().filter(x -> x.amount() >= BIG_AT).toList();
        List<Donation> small = all.stream().filter(x -> x.amount() < BIG_AT).toList();
        if (!big.isEmpty()) {
            // 큰 후원은 묶음 안에서도 한 명씩 이름·금액을 부르고, 작은 후원만 "외 N분"으로
            String head = bigNames(big) + " 후원 감사합니다!";
            if (small.isEmpty()) return head;
            return head + " 그리고 " + small.get(0).nickname() + "님" + (small.size() > 1 ? " 외 " + (small.size() - 1) + "분" : "") + "도 고마워요!";
        }
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
                s.isEmpty() ? 0 : s.get(s.size() / 2), s.isEmpty() ? 0 : s.get(s.size() - 1), quickTurns, quickDonations);
    }
}
