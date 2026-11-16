package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 라이브 한 번 동안의 팬별 기록. 선택 규칙이 읽고(누가 오래 기다렸나), 지표가 읽는다(몇 명이 반응을 받았나).
 * 잠금은 바깥(LiveRoom)이 잡는다 — 이 클래스 자체는 스레드 안전하지 않다.
 */
public class FanLedger {

    /** 팬 한 명의 이번 라이브 기록 */
    public static final class Fan {
        public final String viewerId;
        public String nickname;
        public int chatCount;
        public int giftTotal;
        /** 목소리로 이름이 불린 횟수 (가장 강한 인정) */
        public int reactedCount;
        /** 화면에 "솔라가 봤어요 ❤"로 이름이 뜬 횟수 */
        public int screenAcked;
        /** 묶음 인정("라면 얘기한 분들 다 봤어요")에 들어간 횟수 */
        public int groupAcked;
        /** 마지막으로 반응 받은 시각 (없으면 -1) */
        public long lastReactedAtMs = -1;
        /** 반응을 못 받은 채로 기다리기 시작한 시각 = 마지막 반응 뒤 첫 채팅 시각 (기다리는 중이 아니면 -1) */
        public long waitingSinceMs = -1;

        Fan(String viewerId) {
            this.viewerId = viewerId;
        }
    }

    private final Map<String, Fan> fans = new HashMap<>();
    /** 반응 받은 채팅들의 대기 시간 (ms) — p50/p95 계산용 */
    private final List<Long> reactionWaits = new ArrayList<>();

    void onChat(Chat c) {
        Fan f = fans.computeIfAbsent(c.viewerId(), Fan::new);
        f.nickname = c.nickname();
        f.chatCount++;
        f.giftTotal += c.gift();
        if (f.waitingSinceMs < 0) f.waitingSinceMs = c.receivedAtMs();
    }

    void onReacted(Chat c, long nowMs) {
        Fan f = fans.get(c.viewerId());
        f.reactedCount++;
        f.lastReactedAtMs = nowMs;
        f.waitingSinceMs = -1;
        reactionWaits.add(nowMs - c.receivedAtMs());
    }

    void onScreenAck(String viewerId) {
        Fan f = fans.get(viewerId);
        if (f != null) f.screenAcked++;
    }

    void onGroupAck(String viewerId) {
        Fan f = fans.get(viewerId);
        if (f != null) f.groupAcked++;
    }

    /** 목소리·화면·묶음 중 하나라도 인정을 받은 팬 수 */
    public int anyAckFans() {
        int n = 0;
        for (Fan f : fans.values()) if (f.reactedCount + f.screenAcked + f.groupAcked > 0) n++;
        return n;
    }

    public Fan get(String viewerId) {
        return fans.get(viewerId);
    }

    public Collection<Fan> all() {
        return Collections.unmodifiableCollection(fans.values());
    }

    public int chatters() {
        return fans.size();
    }

    public int reactedFans() {
        int n = 0;
        for (Fan f : fans.values()) if (f.reactedCount > 0) n++;
        return n;
    }

    /** 반응 대기 시간의 q 분위수 (0~1). 아직 반응이 없으면 0 */
    public long waitPercentile(double q) {
        if (reactionWaits.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(reactionWaits);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
