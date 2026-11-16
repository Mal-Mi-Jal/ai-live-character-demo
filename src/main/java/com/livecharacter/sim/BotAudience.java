package com.livecharacter.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 봇 시청자 N명. 각자 평균 간격이 다르게 무작위로 채팅·선물을 보낸다.
 * 시드가 같으면 같은 봇, 같은 채팅이 나온다 → 규칙 비교를 재현할 수 있다.
 *
 * 유형 (비율은 데모용 가정 — 실제 분포가 아니다):
 *  - REGULAR 단골 10%: 평균 8초마다 채팅
 *  - NORMAL 보통 68%: 평균 25초마다
 *  - QUIET 조용한 팬 20%: 평균 90초마다 (라이브 동안 몇 번 안 말한다)
 *  - WHALE 큰손 2%: 평균 12초마다, 채팅의 60%가 선물
 */
public class BotAudience {

    public enum Kind { REGULAR, NORMAL, QUIET, WHALE }

    public record Bot(String id, String nickname, Kind kind, double meanIntervalMs, double giftChance) {
    }

    /** 이번 스텝에 봇이 보낸 채팅 */
    public record Sent(Bot bot, String text, int gift) {
    }

    private static final String[] ADJ = {"졸린", "배고픈", "신난", "조용한", "반짝", "느긋한", "용감한", "수줍은", "엉뚱한", "다정한"};
    private static final String[] NOUN = {"고양이", "펭귄", "수달", "여우", "토끼", "곰돌이", "부엉이", "햄스터", "고래", "다람쥐"};
    private static final String[] CHATS = {
            "안녕 솔라!", "하이하이", "처음 왔어요", "오늘 저녁 라면 먹었어", "떡볶이 vs 치킨?", "마라탕 먹고 싶다",
            "아이스크림 사러 간다", "롤 랭크 또 졌어", "발로란트 하는 사람?", "마크 집 지었어요", "오늘 야근해서 피곤해",
            "너무 졸려요", "시험 끝났다!", "솔라 별에서는 뭐 먹어?", "노래 불러 줘요", "ㅋㅋㅋㅋ", "귀여워",
            "오늘 날씨 춥다", "솔라 몇 살이야?", "내일 월요일이야…", "김밥 싸 왔어", "배그 치킨 먹었다",
            "솔라 오늘 기분 어때?", "방금 퇴근함", "ㅎㅇ", "고양이 키우는 사람?", "피자 시켰어요"};

    private final List<Bot> bots;
    private final Random rnd;

    public BotAudience(int n, long seed) {
        this.rnd = new Random(seed);
        Random setup = new Random(seed * 31 + 7);
        List<Bot> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double r = setup.nextDouble();
            Kind kind = r < 0.02 ? Kind.WHALE : r < 0.12 ? Kind.REGULAR : r < 0.32 ? Kind.QUIET : Kind.NORMAL;
            double mean = switch (kind) {
                case WHALE -> 12_000;
                case REGULAR -> 8_000;
                case NORMAL -> 25_000;
                case QUIET -> 90_000;
            };
            double giftChance = kind == Kind.WHALE ? 0.6 : 0.02;
            String nick = ADJ[setup.nextInt(ADJ.length)] + NOUN[setup.nextInt(NOUN.length)] + (i + 1);
            list.add(new Bot("bot-" + (i + 1), nick, kind, mean, giftChance));
        }
        this.bots = List.copyOf(list);
    }

    public List<Bot> bots() {
        return bots;
    }

    /**
     * dtMs 동안 누가 채팅했는지. 각 봇이 확률 dt/평균간격으로 한 번 말한다 (포아송 과정의 근사).
     */
    public List<Sent> step(long dtMs) {
        List<Sent> out = new ArrayList<>();
        for (Bot b : bots) {
            if (rnd.nextDouble() < dtMs / b.meanIntervalMs()) {
                int gift = rnd.nextDouble() < b.giftChance() ? giftAmount(b) : 0;
                String text = CHATS[rnd.nextInt(CHATS.length)];
                out.add(new Sent(b, text, gift));
            }
        }
        return out;
    }

    private int giftAmount(Bot b) {
        int[] small = {100, 500, 1000};
        int[] big = {1000, 5000, 10000};
        int[] pool = b.kind() == Kind.WHALE ? big : small;
        return pool[rnd.nextInt(pool.length)];
    }
}
