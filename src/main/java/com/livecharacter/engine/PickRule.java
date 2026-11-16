package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 이번 틱에 캐릭터가 반응할 채팅 k개를 고르는 규칙.
 * candidates는 이미 "시청자당 한 줄"로 정리되어 들어온다 (한 사람이 도배해도 후보는 한 번).
 * 규칙끼리 숫자로 비교하려고 인터페이스로 분리했다 (2일차에 공정 규칙 C 추가).
 */
public interface PickRule {

    String id();

    String label();

    List<Chat> pick(List<Chat> candidates, FanLedger ledger, int k, Random rnd);

    /** (A) 무작위: 이번 틱 후보 중 아무나 k명 */
    PickRule RANDOM = new PickRule() {
        public String id() { return "random"; }
        public String label() { return "A 무작위"; }
        public List<Chat> pick(List<Chat> candidates, FanLedger ledger, int k, Random rnd) {
            List<Chat> copy = new ArrayList<>(candidates);
            // 부분 피셔-예이츠 셔플: 앞 k칸만 섞는다
            int n = Math.min(k, copy.size());
            for (int i = 0; i < n; i++) {
                int j = i + rnd.nextInt(copy.size() - i);
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
        public List<Chat> pick(List<Chat> candidates, FanLedger ledger, int k, Random rnd) {
            return candidates.stream()
                    .sorted(Comparator.comparingInt(Chat::gift).reversed()
                            .thenComparing(Comparator.comparingLong(Chat::seq).reversed()))
                    .limit(k)
                    .toList();
        }
    };

    static List<PickRule> all() {
        return List.of(RANDOM, GIFT_THEN_LATEST);
    }

    static PickRule byId(String id) {
        for (PickRule r : all()) if (r.id().equals(id)) return r;
        throw new IllegalArgumentException("알 수 없는 규칙: " + id);
    }
}
