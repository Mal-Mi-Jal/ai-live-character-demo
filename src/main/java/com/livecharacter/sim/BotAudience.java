package com.livecharacter.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 봇 시청자 N명. 각자 평균 간격이 다르게 무작위로 채팅·선물을 보낸다.
 * 시드가 같으면 같은 봇, 같은 채팅이 나온다 → 규칙 비교를 재현할 수 있다.
 *
 * realistic=false(기본): 시뮬레이션 테스트용 고정 27문장·고정 닉네임·원래 속도 (기존 숫자 재현 유지).
 * realistic=true(화면 데모):
 *  - 실제 방송처럼 눈팅 시청자를 반영한 읽기 편한 채팅 속도 (300명 기준 초당 약 3~4줄)
 *  - 방송 단계별 분위기 전환 (PRE_LIVE 대기 → OPENING 인사 → LIVE 본 방송 → CLOSING 배웅)
 *  - 한국 라이브 방송 스타일의 다양한 닉네임, 성향별 말투, 조합형 채팅, 자연스러운 후원 메시지
 */
public class BotAudience {

    public enum Kind { REGULAR, NORMAL, QUIET, WHALE }

    /** 방송 진행 단계 (realistic=true 데모에서만 분위기·속도 반영) */
    public enum Phase { PRE_LIVE, OPENING, LIVE, CLOSING }

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

    // ---------- 화면 데모용 현실적인 닉네임·채팅 조합 풀 ----------
    private static final String[] KR_NICK_A = {
            "새벽", "달빛", "망고", "치즈", "구름", "별사탕", "라떼", "민트", "노을", "보리",
            "솜사탕", "우주", "초코", "딸기", "모찌", "밤하늘", "유자", "소금", "도토리", "말차"};
    private static final String[] KR_NICK_B = {
            "냥이", "덕후", "산책", "감성", "요정", "집사", "러버", "빵", "한잔", "구경꾼",
            "여행자", "주인", "기록", "조각", "탐험가", "별빛", "팬", "토스트", "마법사", "공방"};
    private static final String[] FUN_NICKS = {
            "퇴근하고싶다", "야식참는중", "집에갈래", "치킨이진리", "오늘도야근", "라면장인",
            "월요병말기", "고양이최고", "이불밖은위험해", "별보러갈래", "떡볶이조아", "카페인충전",
            "주말만기다림", "잠이부족해", "배고픈직장인", "방구석트래블러", "아아수혈중", "코딩노예"};
    private static final String[] EN_PREFIX = {
            "star", "luna", "mint", "haru", "koko", "nori", "sky", "momo", "somi", "yuzu",
            "zero", "nova", "boba", "choco", "ruby", "tofu", "hani", "podo", "dori", "milky"};
    private static final String[] EN_SUFFIX = {"_tv", "_kr", "99", "07", "24", "_x", "01", "_log", "88", "_play", "77", ""};

    private static final String[] PRE_LIVE_CHATS = {
            "ㅎㅇㅇ", "ㅎㅇ", "오 방송 켰네~", "솔라 기다리는 중!", "방송 켜졌다ㅎㅎ",
            "1등!", "대기중~", "오 솔라 왔다!", "오늘도 출석!", "알림 뜨자마자 왔어요"};
    private static final String[] OPENING_CHATS = {
            "하이하이~", "안녕 솔라!!", "오늘도 반가워요!", "솔라 왔다!", "별빛 라디오 출석!",
            "ㅎㅇㅎㅇ", "기다렸어 솔라야~", "오늘 목소리 좋다!", "방갑습니다~"};
    private static final String[] CLOSING_CHATS = {
            "수고했어요!", "잘 자 솔라~", "오늘 진짜 재밌었다!", "다음 방송도 올게요!", "빠이빠이 반짝!"};

    private static final String[] FOOD_ITEMS = {"라면", "치킨", "떡볶이", "피자", "마라탕", "김밥", "아이스크림", "초밥", "국밥", "파스타"};
    private static final String[] FOOD_PRE = {"오늘 저녁 ", "방금 ", "아 ", "퇴근하고 ", "지금 ", ""};
    private static final String[] FOOD_POST = {" 먹었어요!", " 시켜먹음ㅋㅋ", " 먹고 싶다ㅠ", " 땡기네", " 먹었지롱", " 포장해옴!", " 진짜 맛있었다"};
    /** 채팅에서 자연스럽게 밸런스 게임 떡밥이 튀어나오도록 섞어 두는 일상 멘트 */
    private static final String[] SEED_TOPIC_CHATS = {
            "아 오늘 탕수육 시킬까 고민중", "방금 탕수육 먹었는데 꿀맛", "민트초코 아이스크림 땡기네ㅋㅋ",
            "여름엔 역시 에어컨 밑이 천국이지", "겨울 이불 속에서 귤 까먹고 싶다", "나 완전 올빼미형이라 밤이 제일 좋음",
            "이번 휴가엔 바다 보러 가고 싶다!", "오늘 치킨 vs 라면 진짜 고민되네"};

    private static final String[] GAME_ITEMS = {"롤", "발로란트", "마크", "배그"};
    private static final String[] GAME_POST = {" 하는 사람?", " 연패해서 멘탈 나감ㅠ", " 랭크 돌리는 중", " 같이 할 사람 구함", " 오늘 잘 풀린다ㅋㅋ", " 한 판만 하고 자야지"};

    private static final String[] TIRED_CHATS = {
            "오늘 야근해서 너무 피곤해ㅠ", "방금 퇴근함 살려줘", "너무 졸려요…", "오늘 진짜 지쳤어",
            "힘들다 솔라 보면서 힐링중", "야근 끝나고 겨우 왔다", "월요일 왜 이렇게 피곤하지", "눈 감겨요 졸려ㅠ"};
    private static final String[] GREET_CHATS = {
            "안녕 솔라!", "하이하이~", "처음 왔어요!", "솔라 방갑!", "오늘도 출석!", "ㅎㅇㅎㅇ", "지나가다 들어왔어요 안녕!"};
    private static final String[] REGULAR_CHATS = {
            "솔라 오늘도 왔네!", "별빛 라디오 출석체크~", "꼬리별 오늘도 열일하네ㅋㅋ", "어제도 봤는데 오늘도 반가워!",
            "솔라 텐션 오늘 좋다ㅋㅋ", "단골 왔습니다~", "오늘도 라디오 켜놓고 쉬는 중", "솔라 방송이 제일 편해"};
    private static final String[] QUIET_CHATS = {
            "ㅎㅇ", "안녕", "ㅋㅋㅋ", "오", "라면 먹음", "치킨", "졸려요", "귀엽네", "반가워요", "ㅇㅇ"};
    private static final String[] ASK_SOLA_CHATS = {
            "솔라 별에서는 뭐 먹어?", "솔라 몇 살이야?", "노래 불러 줘요!", "오늘 저녁 메뉴 추천해줘?",
            "솔라 오늘 기분 어때?", "솔라 어디서 왔어?", "지구에서 제일 신기한 게 뭐야?", "별빛은 무슨 맛이야?"};
    private static final String[] CASUAL_CHATS = {
            "ㅋㅋㅋㅋ", "귀여워ㅠㅠ", "오늘 날씨 진짜 춥다", "시험 끝났다!!", "고양이 키우는 사람?",
            "내일 벌써 월요일이야…", "방금 샤워하고 왔음", "아 커피 마시고 싶다", "솔라 말투 중독성 있네",
            "오늘 방송 분위기 좋다", "ㄹㅇㅋㅋ", "오늘 하루도 끝~", "우주 얘기 더 해줘!"};
    private static final String[] DONATE_MESSAGES = {
            "솔라 오늘 방송 진짜 재밌어요!", "치킨 사먹으라고 보내는데 별빛만 먹는다며ㅋㅋ", "야근 끝나고 피곤했는데 힐링하고 가요",
            "시험 끝났다! 축하해줘 솔라야!", "오늘도 반짝반짝 힘내요 응원할게!", "저녁 라면 먹으면서 보는 중~",
            "솔라 너무 귀여워서 보냅니다ㅋㅋ", "롤 연패했는데 솔라 보고 기분 풀렸어요", "별빛 라디오 최고! 오래 방송해줘요",
            "퇴근 기념 후원! 오늘도 반가워요"};

    private static final Map<String, List<String>> QUESTION_ANSWERS = Map.of(
            "dinner", List.of(
                    "오늘 저녁 라면 끓여먹었어요!", "방금 치킨 시켜먹음ㅋㅋ", "떡볶이 포장해왔지롱", "피자 두 조각 먹었다",
                    "마라탕 2단계 먹었어요", "김밥 간단하게 먹음!", "그냥 집밥 먹었어요", "초밥 먹으러 갔다옴!",
                    "뜨끈한 국밥 먹었지ㅋㅋ", "파스타 만들어 먹었어요", "라면에 계란 넣어먹음", "양념치킨 먹었다!",
                    "로제떡볶이 최고", "페퍼로니 피자요!", "아이스크림으로 때웠어요ㅋㅋ"),
            "game", List.of(
                    "요즘 롤만 주구장창 해요", "발로란트 랭크 돌리는 중!", "마크에서 집 짓고 있어요", "배그 치킨 먹으러 감ㅋㅋ",
                    "롤 칼바람이 제일 재밌음", "발로란트 에임 연습중ㅠ", "마크 힐링돼서 좋아요", "배그 듀오 하는 중!"),
            "mood", List.of(
                    "오늘 완전 좋았어요!", "진짜 신나는 하루였음ㅋㅋ", "야근해서 너무 피곤해요…", "좀 힘들었는데 솔라 보고 나아짐",
                    "오늘 최고였어!!", "졸려요 눈 감길듯ㅠ", "행복한 하루였어요~", "지쳤어요 푹 쉬고 싶다"),
            "weekend", List.of(
                    "무조건 집에서 쉴래요ㅋㅋ", "오랜만에 친구 만나요!", "바다 놀러 가기로 했음", "시험 공부해야 돼요ㅠ",
                    "주말 내내 게임 할 거예요", "그냥 침대에 누워 있을 듯", "주말엔 밀린 잠 자야지", "친구랑 약속 있어요!",
                    "과제 폭탄이라 공부해요…", "여행 가려고 짐 싸는 중!"),
            "pet", List.of(
                    "고양이 두 마리랑 살아요!", "강아지 키워요 진짜 귀여움", "햄스터 키우는 중!", "금붕어 있어요~",
                    "아직 없어요ㅠ 키우고 싶다", "우리집 냥이 지금 자고있음", "멍멍이 산책 다녀왔어요!", "고양이 집사 3년차입니다"),
            "season", List.of(
                    "봄이 제일 좋아요 따뜻해서", "무조건 여름!!", "가을 바람이 최고지", "겨울 좋아해요 붕어빵ㅋㅋ",
                    "봄 벚꽃 시즌이 좋아요", "여름 바다 최고!", "가을 감성 못 참지", "눈 오는 겨울이요!"),
            "music", List.of(
                    "요즘 발라드만 들어요", "아이돌 노래 무한반복 중!", "힙합 들으면서 출근해요", "애니 OST가 최고지ㅋㅋ",
                    "밴드 음악 좋아해요!", "케이팝 신곡 듣는 중", "잔잔한 발라드요~", "록 밴드 노래 자주 들어요"),
            "snack", List.of(
                    "치킨 땡긴다 참아야 하는데", "라면 하나 끓일까 고민중ㅋㅋ", "피자 한 판 시킬까", "떡볶이 먹고 싶다ㅠ",
                    "족발에 막국수 땡기네", "보쌈 먹고 싶어요!", "야식은 역시 치킨이지", "매운 라면 땡긴다!")
    );

    private final List<Bot> bots;
    private final Map<String, Kind> kindById;
    private final Random rnd;
    private final boolean realistic;
    private volatile Phase phase = Phase.LIVE;
    private volatile List<String> answers;
    private volatile String activeQuestionId;
    public static final double ANSWER_CHANCE = 0.65;

    private record Cheer(List<String> lines, double chance, long untilMs) {
    }

    private volatile Cheer cheer;

    public void setPhase(Phase phase) {
        this.phase = phase == null ? Phase.LIVE : phase;
    }

    public Phase phase() {
        return phase;
    }

    public void cheer(List<String> lines, double chance, long durationMs) {
        if (lines == null || lines.isEmpty()) return;
        this.cheer = new Cheer(lines, chance, System.currentTimeMillis() + durationMs);
    }

    public void answering(List<String> answers) {
        this.activeQuestionId = null;
        this.answers = answers;
    }

    public void answeringQuestion(String questionId, List<String> fallbackAnswers) {
        this.activeQuestionId = questionId;
        this.answers = fallbackAnswers;
    }

    public BotAudience(int n, long seed) {
        this(n, seed, 1.0, false);
    }

    public BotAudience(int n, long seed, double giftScale) {
        this(n, seed, giftScale, false);
    }

    public BotAudience(int n, long seed, double giftScale, boolean realistic) {
        this.rnd = new Random(seed);
        this.realistic = realistic;
        Random setup = new Random(seed * 31 + 7);
        List<Bot> list = new ArrayList<>(n);
        Map<String, Kind> kinds = new HashMap<>();
        java.util.Set<String> usedNicks = new java.util.HashSet<>();
        // 실제 방송엔 눈팅 시청자가 많아 300명 기준 초당 약 3~4줄이 자연스럽다 (시뮬레이션 realistic=false는 1.0 유지)
        double paceScale = realistic ? 3.2 : 1.0;
        for (int i = 0; i < n; i++) {
            double r = setup.nextDouble();
            Kind kind = r < 0.02 ? Kind.WHALE : r < 0.12 ? Kind.REGULAR : r < 0.32 ? Kind.QUIET : Kind.NORMAL;
            double mean = switch (kind) {
                case WHALE -> 12_000 * paceScale;
                case REGULAR -> 8_000 * paceScale;
                case NORMAL -> 25_000 * paceScale;
                case QUIET -> 90_000 * paceScale;
            };
            double giftChance = (kind == Kind.WHALE ? 0.6 : 0.02) * giftScale;
            String nick = realistic ? realisticNick(setup, i, usedNicks)
                    : ADJ[setup.nextInt(ADJ.length)] + NOUN[setup.nextInt(NOUN.length)] + (i + 1);
            String id = "bot-" + (i + 1);
            list.add(new Bot(id, nick, kind, mean, giftChance));
            kinds.put(id, kind);
        }
        this.bots = List.copyOf(list);
        this.kindById = Map.copyOf(kinds);
    }

    private static String realisticNick(Random setup, int idx, java.util.Set<String> used) {
        for (int attempt = 0; attempt < 6; attempt++) {
            int style = setup.nextInt(10);
            String base;
            if (style < 4) {
                base = KR_NICK_A[setup.nextInt(KR_NICK_A.length)] + KR_NICK_B[setup.nextInt(KR_NICK_B.length)];
                if (setup.nextInt(3) == 0) base += (setup.nextInt(90) + 10);
            } else if (style < 6) {
                base = EN_PREFIX[setup.nextInt(EN_PREFIX.length)] + EN_SUFFIX[setup.nextInt(EN_SUFFIX.length)];
            } else if (style < 8) {
                base = FUN_NICKS[setup.nextInt(FUN_NICKS.length)];
                if (used.contains(base)) base += (setup.nextInt(90) + 10);
            } else {
                base = ADJ[setup.nextInt(ADJ.length)] + NOUN[setup.nextInt(NOUN.length)];
                if (used.contains(base)) base += (setup.nextInt(90) + 10);
            }
            if (base.length() > 12) base = base.substring(0, 12);
            if (used.add(base)) return base;
        }
        String fallback = KR_NICK_A[idx % KR_NICK_A.length] + (idx + 1);
        used.add(fallback);
        return fallback;
    }

    public List<Bot> bots() {
        return bots;
    }

    public Kind kindOf(String viewerId) {
        return kindById.get(viewerId);
    }

    public List<Sent> step(long dtMs) {
        return step(dtMs, b -> true);
    }

    public List<Sent> step(long dtMs, java.util.function.Predicate<Bot> present) {
        List<Sent> out = new ArrayList<>();
        Phase ph = this.phase;
        // 방송 준비중(PRE_LIVE)엔 조용히 한두 명씩 들어오며 대기 채팅만
        double rateMul = (realistic && ph == Phase.PRE_LIVE) ? 0.22 : 1.0;
        for (Bot b : bots) {
            if (!present.test(b)) continue;
            if (rnd.nextDouble() < (dtMs * rateMul) / b.meanIntervalMs()) {
                boolean allowGift = !realistic || ph == Phase.LIVE;
                int gift = (allowGift && rnd.nextDouble() < b.giftChance()) ? giftAmount(b) : 0;
                String text = pickText(b, gift, ph);
                out.add(new Sent(b, text, gift));
            }
        }
        return out;
    }

    private String pickText(Bot b, int gift, Phase ph) {
        if (realistic) {
            if (ph == Phase.PRE_LIVE) return PRE_LIVE_CHATS[rnd.nextInt(PRE_LIVE_CHATS.length)];
            if (ph == Phase.CLOSING) return CLOSING_CHATS[rnd.nextInt(CLOSING_CHATS.length)];
            if (gift > 0) return DONATE_MESSAGES[rnd.nextInt(DONATE_MESSAGES.length)];
        }
        List<String> a = answers;
        if (a != null && rnd.nextDouble() < ANSWER_CHANCE) {
            if (realistic && activeQuestionId != null) {
                List<String> varied = QUESTION_ANSWERS.get(activeQuestionId);
                if (varied != null && !varied.isEmpty()) return varied.get(rnd.nextInt(varied.size()));
            }
            return a.get(rnd.nextInt(a.size()));
        }
        Cheer ch = cheer;
        if (ch != null && System.currentTimeMillis() < ch.untilMs() && rnd.nextDouble() < ch.chance()) {
            return ch.lines().get(rnd.nextInt(ch.lines().size()));
        }
        if (realistic && ph == Phase.OPENING && rnd.nextDouble() < 0.72) {
            return OPENING_CHATS[rnd.nextInt(OPENING_CHATS.length)];
        }
        return realistic ? realisticChat(b) : CHATS[rnd.nextInt(CHATS.length)];
    }

    private String realisticChat(Bot b) {
        if (b.kind() == Kind.QUIET && rnd.nextDouble() < 0.6) {
            return QUIET_CHATS[rnd.nextInt(QUIET_CHATS.length)];
        }
        if (b.kind() == Kind.REGULAR && rnd.nextDouble() < 0.35) {
            return REGULAR_CHATS[rnd.nextInt(REGULAR_CHATS.length)];
        }
        int pick = rnd.nextInt(100);
        if (pick < 24) {
            return FOOD_PRE[rnd.nextInt(FOOD_PRE.length)]
                    + FOOD_ITEMS[rnd.nextInt(FOOD_ITEMS.length)]
                    + FOOD_POST[rnd.nextInt(FOOD_POST.length)];
        }
        if (pick < 36) return SEED_TOPIC_CHATS[rnd.nextInt(SEED_TOPIC_CHATS.length)];
        if (pick < 50) return GAME_ITEMS[rnd.nextInt(GAME_ITEMS.length)] + GAME_POST[rnd.nextInt(GAME_POST.length)];
        if (pick < 62) return TIRED_CHATS[rnd.nextInt(TIRED_CHATS.length)];
        if (pick < 72) return GREET_CHATS[rnd.nextInt(GREET_CHATS.length)];
        if (pick < 84) return ASK_SOLA_CHATS[rnd.nextInt(ASK_SOLA_CHATS.length)];
        return CASUAL_CHATS[rnd.nextInt(CASUAL_CHATS.length)];
    }

    private int giftAmount(Bot b) {
        int[] small = {100, 500, 1000};
        int[] big = {1000, 5000, 10000};
        int[] pool = b.kind() == Kind.WHALE ? big : small;
        return pool[rnd.nextInt(pool.length)];
    }
}
