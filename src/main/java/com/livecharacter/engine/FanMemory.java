package com.livecharacter.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 팬별 관계 기록. 라이브가 바뀌어도(규칙을 바꿔 숫자를 리셋해도) 남는다.
 *
 * 무엇을 저장하나: 채팅 원문이 아니라, 코드가 정해 둔 키워드 목록(LineWriter의 음식·게임·피곤 등)에
 * 걸린 단어만. → 전화번호나 사적인 문장이 기억에 남을 수 없고, 캐릭터가 꺼낼 수 있는 사실도 이 목록뿐이다.
 *
 * 누구로 묶나: 시청자 id로만 묶는다. 닉네임은 겹치거나 바뀔 수 있어서, 닉네임으로 합치면
 * 남의 기억을 그 사람에게 말하는 사고가 난다 (플랫폼 간 연결도 같은 원칙: 팬이 직접 연결했을 때만).
 */
public class FanMemory {

    record Mem(int count, long firstSeenMs) {
    }

    /** 방금 한 말을 "지난번"이라고 말하지 않도록, 이만큼 지난 기억만 꺼낸다 */
    public static final long MIN_AGE_MS = 60_000;

    private final Map<String, Map<String, Mem>> byFan = new HashMap<>();

    void remember(String viewerId, String keyword, long nowMs) {
        if (keyword == null) return;
        byFan.computeIfAbsent(viewerId, k -> new LinkedHashMap<>())
                .merge(keyword, new Mem(1, nowMs), (old, n) -> new Mem(old.count() + 1, old.firstSeenMs()));
    }

    /**
     * 이 팬에게 꺼낼 기억 하나. 지금 말한 주제와 다른 것 중, 충분히 오래된 것에서 가장 자주 말한 키워드.
     * 확신할 만한 게 없으면 null → 캐릭터는 기억 이야기를 하지 않는다.
     */
    String recall(String viewerId, String currentKeyword, long nowMs) {
        Map<String, Mem> m = byFan.get(viewerId);
        if (m == null) return null;
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Mem> e : m.entrySet()) {
            if (e.getKey().equals(currentKeyword)) continue;
            if (nowMs - e.getValue().firstSeenMs() < MIN_AGE_MS) continue;
            if (e.getValue().count() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue().count();
            }
        }
        return best;
    }

    public int fans() {
        return byFan.size();
    }
}
