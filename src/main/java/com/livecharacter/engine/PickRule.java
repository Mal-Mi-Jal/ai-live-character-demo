package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 이번 틱에 캐릭터가 반응할 채팅 k개를 고르는 규칙.
 * 규칙끼리 숫자로 비교하려고 인터페이스로 분리했다 (스프링 비유: 전략 패턴 빈 여러 개).
 */
public interface PickRule {

    /**
     * @param fresh 이번 틱에 들어온 채팅 (시청자당 한 줄)
     * @param pool  아직 반응 못 받은 최근 채팅 전체 (fresh 포함, 시청자당 한 줄). 공정 규칙만 쓴다
     */
    record Context(List<Chat> fresh, List<Chat> pool, FanLedger ledger, int k, Random rnd, long nowMs) {
    }

    String id();

    String label();

    List<Chat> pick(Context ctx);

    /** (A) 무작위: 이번 틱 후보 중 아무나 k명 */
    PickRule RANDOM = new PickRule() {
        public String id() { return "random"; }
        public String label() { return "A 무작위"; }
        public List<Chat> pick(Context ctx) {
            List<Chat> copy = new ArrayList<>(ctx.fresh());
            // 부분 피셔-예이츠 셔플: 앞 k칸만 섞는다
            int n = Math.min(ctx.k(), copy.size());
            for (int i = 0; i < n; i++) {
                int j = i + ctx.rnd().nextInt(copy.size() - i);
                Chat t = copy.get(i);
                copy.set(i, copy.get(j));
                copy.set(j, t);
            }
            return List.copyOf(copy.subList(0, n));
        }
    };

    /** (B) 선물순 → 같으면 최신순: 흔한 라이브 방식. 선물한 사람과 마지막에 말한 사람이 화면을 차지한다 */
    PickRule GIFT_THEN_LATEST = new PickRule() {
        public String id() { return "gift"; }
        public String label() { return "B 선물·최신순"; }
        public List<Chat> pick(Context ctx) {
            return ctx.fresh().stream()
                    .sorted(Comparator.comparingInt(Chat::gift).reversed()
                            .thenComparing(Comparator.comparingLong(Chat::seq).reversed()))
                    .limit(ctx.k())
                    .toList();
        }
    };

    /** (C) 공정 규칙 — 점수는 FairScore 참고 */
    PickRule FAIR = new PickRule() {
        public String id() { return "fair"; }
        public String label() { return "C 공정"; }
        public List<Chat> pick(Context ctx) {
            record Scored(Chat chat, double score) {
            }
            List<Scored> scored = new ArrayList<>();
            for (Chat c : ctx.pool()) {
                // 동점일 때 순서가 늘 같은 사람에게 유리하지 않게 아주 작은 무작위 값을 더한다
                scored.add(new Scored(c, FairScore.of(c, ctx.ledger().get(c.viewerId()), ctx.nowMs())
                        + ctx.rnd().nextDouble() * 0.01));
            }
            return scored.stream()
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(ctx.k())
                    .map(Scored::chat)
                    .toList();
        }
    };

    static List<PickRule> all() {
        return List.of(RANDOM, GIFT_THEN_LATEST, FAIR);
    }

    static PickRule byId(String id) {
        for (PickRule r : all()) if (r.id().equals(id)) return r;
        throw new IllegalArgumentException("알 수 없는 규칙: " + id);
    }

    /**
     * 공정 점수. 숫자는 "몇 초 기다린 것과 같은가"로 맞췄다 → 면접에서 설명하기 쉽게.
     *  - 이번 라이브에서 한 번도 반응을 못 받았으면 +60 (= 60초 기다린 것만큼)
     *  - 기다린 시간 1초당 +1, 최대 60
     *  - 이미 반응 받은 횟수 1번당 -40 (같은 사람이 계속 뽑히지 않게)
     *  - 선물은 보너스: 1,000원당 +5, 최대 +30 (선물이 묻히지는 않지만, 큰손이 화면을 독점하지는 못한다)
     */
    final class FairScore {
        static final double FIRST_TIME = 60;
        static final double MAX_WAIT_SEC = 60;
        static final double PER_REACTION = 40;
        static final double PER_LIGHT_ACK = 10;
        static final double GIFT_PER_1000 = 5;
        static final double MAX_GIFT = 30;

        private FairScore() {
        }

        static double of(Chat c, FanLedger.Fan fan, long nowMs) {
            int reacted = fan == null ? 0 : fan.reactedCount;
            // 기다린 시간 = "이 채팅"이 답을 못 받은 시간. 처음엔 "그 팬이 처음 말한 뒤로"를 썼는데,
            // 음성 모드(자리가 적을 때) 시뮬레이션에서 자주 말하는 팬이 계속 이겨 조용한 팬이 무작위보다 덜 뽑혔다.
            // 채팅 기준이면 많이 말해도 점수가 오르지 않고, 조용한 팬의 한 마디가 시간이 지나며 앞으로 온다
            double waitSec = Math.min(MAX_WAIT_SEC, Math.max(0, (nowMs - c.receivedAtMs()) / 1000.0));
            double gift = Math.min(MAX_GIFT, c.gift() / 1000.0 * GIFT_PER_1000);
            // 화면·묶음으로 이미 인정받은 팬은 조금 뒤로 (목소리 기회는 남겨 둔다)
            int lighter = fan == null ? 0 : fan.screenAcked + fan.groupAcked;
            return (reacted == 0 ? FIRST_TIME : 0) + waitSec - PER_REACTION * reacted - PER_LIGHT_ACK * lighter + gift;
        }
    }
}
