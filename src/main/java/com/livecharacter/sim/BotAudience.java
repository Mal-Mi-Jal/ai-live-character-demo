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
    /** 캐릭터가 질문 중이면 봇이 이 답들 중에서 고르기도 한다 (null이면 평소 채팅만) */
    private volatile List<String> answers;
    /** 질문 중일 때 봇 채팅 중 답의 비율 (가정) */
    public static final double ANSWER_CHANCE = 0.6;

    /** 잠깐 동안의 반응 (인사·웃음·배웅). 질문 답보다 우선순위가 낮다 */
    private record Cheer(List<String> lines, double chance, long untilMs) {
    }

    private volatile Cheer cheer;

    /**
     * 캐릭터의 말에 봇이 잠깐 따라 반응한다 (방송 시작이면 인사, 이야기 뒤엔 "ㅋㅋㅋ", 큰 후원엔 "오 만원!").
     * 화면 데모용. 처음엔 봇이 정해진 문장만 무작위로 쳐서, 캐릭터가 무슨 말을 해도 채팅이 따라오지 않았다
     */
    public void cheer(List<String> lines, double chance, long durationMs) {
        this.cheer = new Cheer(lines, chance, System.currentTimeMillis() + durationMs);
    }

    /** 캐릭터가 질문을 던지면 봇도 그 질문에 답하게 한다. null이면 그만 */
    public void answering(List<String> answers) {
        this.answers = answers;
    }

    public BotAudience(int n, long seed) {
        this(n, seed, 1.0);
    }

    /**
     * @param giftScale 선물 확률 배율. 시뮬레이션은 1.0, 화면 데모는 후원이 너무 몰리지 않게 낮춘다
     *                  (1.0이면 300명일 때 분당 약 18건 — 음성으로는 분당 10번 남짓만 말할 수 있다)
     */
    public BotAudience(int n, long seed, double giftScale) {
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
            double giftChance = (kind == Kind.WHALE ? 0.6 : 0.02) * giftScale;
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
        return step(dtMs, b -> true);
    }

    /** present가 false인 봇은 이번 라이브에 오지 않은 것으로 보고 건너뛴다 (다음 라이브 복귀 시뮬레이션용) */
    public List<Sent> step(long dtMs, java.util.function.Predicate<Bot> present) {
        List<Sent> out = new ArrayList<>();
        for (Bot b : bots) {
            if (!present.test(b)) continue;
            if (rnd.nextDouble() < dtMs / b.meanIntervalMs()) {
                int gift = rnd.nextDouble() < b.giftChance() ? giftAmount(b) : 0;
                List<String> a = answers;
                Cheer ch = cheer;
                String text;
                if (a != null && rnd.nextDouble() < ANSWER_CHANCE) text = a.get(rnd.nextInt(a.size()));
                else if (ch != null && System.currentTimeMillis() < ch.untilMs() && rnd.nextDouble() < ch.chance())
                    text = ch.lines().get(rnd.nextInt(ch.lines().size()));
                else text = CHATS[rnd.nextInt(CHATS.length)];
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
