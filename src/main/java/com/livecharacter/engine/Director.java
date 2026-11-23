package com.livecharacter.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 진행자: 다음 차례에 무엇을 할지 정한다 (순수 Java, 시계는 밖에서 받음).
 *
 * 사람 스트리머처럼 "채팅 하나에 답하기"만 반복하지 않고,
 *  - 오프닝 인사 → 인사 티키타카 → 본 방송
 *  - 솔라 주도 에피소드: 도입 떡밥 던지기 → 에피소드에 반응한 시청자 채팅 2회 콕 집어 티키타카 → 뒷이야기 마무리
 *  - 자연스러운 채팅 떡밥 연계 밸런스 게임: 채팅 속 탕수육·민초·이불 같은 떡밥을 발견해 투표 오픈 →
 *    투표 중 솔라 본인 취향·유도 멘트로 티키타카 → 중간 판세 중계 → 최종 판결
 *  - 일반 질문 코너: 질문 → 답 모으기 → 정리 → 이어 말하기
 */
public class Director {

    public enum Kind {
        REACT, ASK, ANSWERS, TALK, STORY, GROUP, HELLO,
        BALANCE_START, BALANCE_MID, BALANCE_END,
        EPISODE_START, EPISODE_END
    }

    public record Question(String id, String label, String ask, Map<String, List<String>> buckets,
                           List<String> botAnswers, String comment, List<String> quickReplies) {

        public String bucketOf(String text) {
            for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
                for (String kw : e.getValue()) if (text.contains(kw)) return e.getKey();
            }
            return null;
        }
    }

    /** 실시간 밸런스 게임 (A vs B) + 채팅 떡밥 연계 + 투표 중 솔라 티키타카 멘트 */
    public record BalanceGame(String id, String title,
                              List<String> triggerKws, String segueIntro, String intro,
                              String optionA, List<String> kwA, List<String> botA, String banterA, String winA,
                              String optionB, List<String> kwB, List<String> botB, String banterB, String winB,
                              String midFlavor, String tieLine) {

        public String sideOf(String text) {
            for (String kw : kwB) if (text.contains(kw)) return "B";
            for (String kw : kwA) if (text.contains(kw)) return "A";
            return null;
        }

        public boolean triggeredBy(String text) {
            for (String kw : triggerKws) if (text.contains(kw)) return true;
            return false;
        }

        public List<String> allBotChats() {
            List<String> out = new ArrayList<>(botA.size() + botB.size());
            out.addAll(botA);
            out.addAll(botB);
            return List.copyOf(out);
        }
    }

    /** 화면 표시용 밸런스 게임 실시간 집계 */
    public record BalanceView(String title, String optionA, String optionB, int countA, int countB, long remainingMs) {
    }

    /** 밸런스 게임 최종 집계 결과 */
    public record BalanceResult(BalanceGame game, int countA, int countB, List<String> voters, String line) {
    }

    /** 에피소드 중 시청자 반응 키워드에 딱 맞춰 솔라가 받아치는 티키타카 짝 */
    public record FollowUp(List<String> keywords, String reply) {
        public boolean matches(String text) {
            for (String k : keywords) if (text.contains(k)) return true;
            return false;
        }
    }

    /** 솔라 주도 멀티 턴 미니 에피소드 (도입 → 시청자 반응 콕 집어 티키타카 → 결말) */
    public record Episode(String title, String part1, List<String> cheers1,
                          List<FollowUp> followUps, String fallbackReply,
                          String part2, List<String> cheers2) {

        public boolean matchesReaction(String text) {
            for (FollowUp f : followUps) if (f.matches(text)) return true;
            return false;
        }

        public String replyFor(String chatText, String nickname) {
            String quote = chatText.length() <= 16 ? "\"" + chatText.strip() + "\" " : nickname + "님, ";
            for (FollowUp f : followUps) {
                if (f.matches(chatText)) return quote + f.reply();
            }
            return quote + fallbackReply;
        }
    }

    public record Plan(Kind kind, Question question, Predicate<Chat> prefer, String preface) {
    }

    public record Answers(Question question, Map<String, Integer> counts, int total,
                          List<String> answerers, String highlightNick, String line) {
    }

    public record Talk(Question question, List<Chat> chats, String templateLine) {
        public List<String> nicknames() {
            return chats.stream().map(Chat::nickname).toList();
        }
    }

    public record Status(String topic, boolean asking, long remainingMs, String ask,
                         List<String> quickReplies, BalanceView balance) {
        public Status(String topic, boolean asking, long remainingMs, String ask, List<String> quickReplies) {
            this(topic, asking, remainingMs, ask, quickReplies, null);
        }
    }

    public record Story(String line, List<String> cheers) {
    }

    public static final long ANSWER_MS = 30_000;
    public static final long BALANCE_MS = 32_000;
    public static final long BALANCE_MID_MS = 15_000;
    public static final long TOPIC_MS = 30_000;
    public static final long ASK_GAP_MS = 60_000;
    public static final int ASK_EVERY_TURNS = 8;
    public static final int HOT_MIN = 5;

    static final Question DINNER = new Question("dinner", "저녁 메뉴",
            "다들 오늘 저녁 뭐 먹었어요? 솔라한테 알려 줘요!",
            buckets(LineWriter.FOOD_KW),
            List.of("저녁 라면 먹었어요", "치킨 먹었지", "떡볶이요!", "피자 시켰어요", "마라탕 먹음", "김밥이요", "그냥 밥 먹었어요"),
            "솔라도 언젠가 꼭 구경해 볼래요!",
            List.of("치킨 먹었어요!", "저녁 라면 먹음", "떡볶이요!", "피자 시켰어요", "마라탕 먹었어요"));
    static final Question GAME = new Question("game", "요즘 하는 게임",
            "요즘 무슨 게임 해요? 솔라 추천 받아요!",
            buckets(List.of("롤", "발로란트", "마크", "배그")),
            List.of("롤 해요", "발로란트!", "마크 하는 중", "배그 함", "요즘 롤만 해요"),
            "솔라는 버튼이 너무 많아서 아직 구경만 해요!",
            List.of("요즘 롤 해요", "발로란트!", "마크 집 짓는 중", "배그 해요"));
    static final Question MOOD = new Question("mood", "오늘 기분",
            "오늘 하루 어땠어요? 좋았어요, 피곤했어요?",
            orderedBuckets(new String[][]{
                    {"좋았던 분", "좋았", "좋아", "신나", "행복", "최고"},
                    {"피곤한 분", "피곤", "힘들", "졸려", "졸리", "야근", "지쳤"}}),
            List.of("좋았어요!", "완전 신나요", "피곤해요…", "야근해서 힘들어", "오늘 최고였음", "졸려요"),
            "다들 오늘도 정말 수고 많았어요!",
            List.of("오늘 완전 좋았어요!", "피곤해요…", "야근해서 힘들어요", "그냥 신나요!"));
    static final Question WEEKEND = new Question("weekend", "주말 계획",
            "이번 주말에 뭐 해요? 솔라한테 알려 줘요!",
            orderedBuckets(new String[][]{
                    {"집콕", "집", "쉬", "누워", "잠"}, {"여행", "여행", "바다", "놀러"}, {"공부", "공부", "시험", "과제"},
                    {"약속", "친구", "약속", "데이트"}, {"게임", "게임", "롤"}}),
            List.of("집에서 쉴래요", "친구 만나요", "바다 놀러 가요", "시험 공부해야 돼요", "게임 할 거예요", "그냥 누워 있을 듯"),
            "솔라는 주말에 별똥별 산책 나갈 거예요!",
            List.of("집에서 쉴래요", "친구 만나요!", "바다 놀러 가요", "게임 할 거예요"));
    static final Question PET = new Question("pet", "같이 사는 동물",
            "다들 집에 같이 사는 동물 있어요?",
            orderedBuckets(new String[][]{
                    {"고양이", "고양이", "냥"}, {"강아지", "강아지", "멍"}, {"햄스터", "햄스터"},
                    {"물고기", "물고기", "금붕어"}, {"없음", "없어", "없음"}}),
            List.of("고양이 두 마리요", "강아지 키워요", "햄스터!", "금붕어 있어요", "없어요ㅠ", "냥이 있어요"),
            "솔라는 꼬리별이 제일 친한 친구예요!",
            List.of("고양이 키워요!", "강아지 있어요", "햄스터요!", "아직 없어요ㅠ"));
    static final Question SEASON = new Question("season", "좋아하는 계절",
            "다들 무슨 계절 제일 좋아해요?",
            orderedBuckets(new String[][]{{"봄", "봄"}, {"여름", "여름"}, {"가을", "가을"}, {"겨울", "겨울"}}),
            List.of("봄이 좋아요", "여름!", "가을이 최고", "겨울 좋아해요", "무조건 여름"),
            "솔라 별엔 계절이 없어서 사계절 다 신기해요!",
            List.of("봄이 좋아요", "여름 최고!", "가을이 좋아요", "겨울 좋아해요"));
    static final Question MUSIC = new Question("music", "요즘 듣는 노래",
            "요즘 무슨 노래 자주 들어요?",
            orderedBuckets(new String[][]{
                    {"발라드", "발라드"}, {"아이돌", "아이돌", "케이팝"}, {"힙합", "힙합"},
                    {"애니 노래", "애니", "OST"}, {"밴드", "밴드", "록"}}),
            List.of("발라드요", "아이돌 노래", "힙합 들어요", "애니 OST!", "밴드 음악"),
            "솔라는 별빛 소리만 들어 봤어요! 궁금하다~",
            List.of("발라드 들어요", "아이돌 노래!", "밴드 음악 좋아요", "애니 OST요"));
    static final Question SNACK = new Question("snack", "야식",
            "지금 야식 땡기는 사람? 뭐 먹고 싶어요?",
            orderedBuckets(new String[][]{
                    {"치킨", "치킨"}, {"라면", "라면"}, {"피자", "피자"}, {"떡볶이", "떡볶이"}, {"족발", "족발", "보쌈"}}),
            List.of("치킨 땡긴다", "라면 끓일까", "피자!", "떡볶이 먹고 싶다", "족발이요"),
            "밤에 먹는 음식이 제일 맛있다면서요? 궁금해요!",
            List.of("치킨 땡긴다!", "라면 끓일까", "떡볶이 먹고 싶어요", "족발이요!"));
    static final List<Question> ROTATION = List.of(DINNER, MOOD, GAME, WEEKEND, PET, SEASON, MUSIC, SNACK);

    static final List<BalanceGame> BALANCE_GAMES = List.of(
            new BalanceGame("tangsuyuk", "부먹 vs 찍먹",
                    List.of("탕수육", "치킨", "피자", "떡볶이", "부먹", "찍먹"),
                    "어? 방금 {nick}님이 음식 얘기 꺼내서 생각났어요! 지구엔 탕수육 부먹 vs 찍먹이라는 엄청난 대결이 있다면서요? 다들 무슨 파예요? 투표 한번 열어 볼까요!",
                    "맞다, 음식 얘기하다가 궁금해진 게 있어요! 지구엔 탕수육 부먹 vs 찍먹 대결이 있다면서요? 다들 무슨 파예요? 투표 한번 해 볼까요!",
                    "부먹", List.of("부먹", "부어", "촉촉"),
                    List.of("부먹이 소스 배서 진짜 맛있음", "당연히 부먹이지ㅋㅋ", "촉촉한 부먹파 모여라!", "원래 부어 먹는 요리임!"),
                    "부먹 한 표 추가요! 촉촉하게 소스 배는 게 포인트군요? 찍먹파 분들 반격 안 해요?",
                    "부먹 승리! 소스 목욕한 탕수육이라니 솔라도 구경해 보고 싶어요!",
                    "찍먹", List.of("찍먹", "찍어", "바삭"),
                    List.of("찍먹이 국룰이지ㅋㅋ", "바삭해야지 무조건 찍먹!", "찍먹 아니면 못 먹어ㅠ", "소스는 찍어 먹어야지!"),
                    "오 찍먹 한 표! 솔라도 별빛 조각은 찍어 먹는 편이라 공감돼요~ 부먹파 분들 힘내세요!",
                    "찍먹 승리! 바삭바삭 소리 난다면서요? 솔라도 꼭 들어 보고 싶어요!",
                    "솔라는 별빛 시럽 찍어 먹는 게 좋긴 한데, 지구인들 선택은 어떨까요?",
                    "우와 완전 동점이에요! 역시 지구 최대 난제답네요!"),
            new BalanceGame("mincho", "민초 vs 반민초",
                    List.of("민초", "민트", "아이스크림", "초코"),
                    "어, 방금 {nick}님 채팅 보니까 생각났어요! 지구엔 민초파랑 반민초파가 맨날 싸운다면서요? 여러분은 어느 쪽이에요? 지금 투표 열어 볼게요!",
                    "맞다! 지구 아이스크림 중에 민트초코가 엄청 유명하다면서요? 좋다 민초파, 싫다 반민초파! 투표 한번 열어 볼까요?",
                    "민초파", List.of("민초", "민트", "좋아", "상쾌"),
                    List.of("민초가 최고지 상쾌해!", "민트초코 못 잃어ㅋㅋ", "민초파 출동!", "초코랑 민트 조합 미쳤음"),
                    "민초파 한 표! 시원하고 상쾌한 맛이라니 별빛 바람 같을까요? 반민초파 분들 어때요?",
                    "민초파 승리! 상쾌한 초코 맛이라니 솔라도 궁금해졌어요!",
                    "반민초파", List.of("반민초", "치약", "싫어", "불호"),
                    List.of("반민초!! 치약 맛이잖아ㅠ", "양치하면서 초코 먹는 느낌임", "무조건 반민초ㅋㅋ", "민트는 치약으로만!"),
                    "반민초 한 표요! 치약 맛이라는 비유가 너무 웃겨요ㅋㅋ 민초파 분들 가만 계실 거예요?",
                    "반민초파 승리! 지구인들은 치약 맛을 꽤 경계하는군요? 메모했어요!",
                    "솔라는 아직 치약도 안 써 봐서 둘 다 신기한데요?",
                    "민초랑 반민초가 딱 반반! 별빛 라디오가 둘로 갈라졌어요!"),
            new BalanceGame("weather", "여름 에어컨 vs 겨울 이불",
                    List.of("에어컨", "이불", "춥다", "날씨", "겨울", "여름"),
                    "방금 {nick}님 얘기 들으니까 궁금해졌어요! 한여름 에어컨 밑 vs 한겨울 따뜻한 이불 속, 어느 쪽이 더 천국이에요? 투표 열어 볼게요!",
                    "여러분, 계절 얘기 나와서 그런데! 한여름 시원한 에어컨 밑 vs 한겨울 이불 속, 어디가 최고예요? 지금 투표해 줘요!",
                    "여름 에어컨", List.of("여름", "에어컨", "시원", "아아"),
                    List.of("여름 에어컨 틀고 아이스크림이 최고", "시원한 에어컨 밑이 천국이지ㅋㅋ", "여름 에어컨 한 표!", "에어컨 바람 못 참지"),
                    "여름 에어컨 한 표! 시원한 바람 나오는 기계라니 진짜 탐나요~ 겨울 이불파도 어서 오세요!",
                    "여름 에어컨 승리! 지구인들은 바람 만드는 기계가 있다니 진짜 신기해요!",
                    "겨울 이불", List.of("겨울", "이불", "따뜻", "전기장판", "귤"),
                    List.of("겨울 이불 속에서 귤 까먹기 국룰", "따뜻한 이불 밖은 위험해ㅋㅋ", "겨울 이불 무조건이지!", "전기장판 틀고 겨울 이불!"),
                    "겨울 이불 한 표요! 이불 속에서 귤 까먹는 거 저도 꼭 해 보고 싶어요~ 에어컨파 분발해요!",
                    "겨울 이불 승리! 솔라도 별빛 담요 덮고 이불 속 들어가 보고 싶어요!",
                    "솔라는 우주 추위에 익숙해서 따뜻한 이불이 살짝 끌리긴 해요~",
                    "둘 다 못 고르겠죠? 완전 동점! 역시 쉬는 게 제일 좋은 거예요!"),
            new BalanceGame("nightfood", "평생 라면 vs 평생 치킨",
                    List.of("라면", "치킨", "야식"),
                    "어, {nick}님 덕분에 오늘 최대 고민 떠올랐어요! 평생 하나만 구경할 수 있다면 라면 vs 치킨! 우리 투표로 결판내 볼까요?",
                    "지구 음식 도감 쓰다가 궁금해졌어요! 평생 라면 vs 평생 치킨, 여러분 선택은 어느 쪽이에요? 투표 시작!",
                    "라면", List.of("라면", "국물", "면"),
                    List.of("라면은 못 끊지 국물 최고!", "평생 라면이지 종류가 몇 개인데ㅋㅋ", "라면 한 표요!", "밤엔 역시 라면이지"),
                    "라면 한 표 추가요! 종류가 그렇게 많다면서요? 치킨파 분들 이대로 질 거예요?",
                    "라면 승리! 뜨거운 국물에 꼬불꼬불 면이라니, 지구 발명품 1등이네요!",
                    "치킨", List.of("치킨", "바삭", "양념", "후라이드"),
                    List.of("치킨이 진리지 후라이드 양념 다 있잖아", "평생 치킨 못 참지ㅋㅋ", "무조건 치킨!!", "치킨은 못 이겨ㅋㅋ"),
                    "치킨 한 표요! 양념이랑 후라이드 둘 다 있다니 반칙 아니에요? 라면파 분들 모여 주세요!",
                    "치킨 승리! 지구에서 제일 사랑받는 음식이 치킨 맞군요!",
                    "솔라는 이름 귀여운 쪽에 마음이 가는데… 둘 다 너무 막상막하네요!",
                    "라면이랑 치킨이 동점! 이 정도면 둘 다 먹어야 하는 거 아니에요?"),
            new BalanceGame("chronotype", "아침형 vs 올빼미형",
                    List.of("올빼미", "졸려", "야근", "피곤", "새벽", "밤"),
                    "방금 {nick}님 채팅 보니까 물어보고 싶어졌어요! 상쾌한 아침형 vs 밤에 살아나는 올빼미형, 여기엔 어느 쪽이 많아요? 투표 열어 볼게요!",
                    "여러분은 상쾌한 아침형 인간이에요, 밤에 반짝 살아나는 올빼미형이에요? 궁금해서 투표 열어 봤어요!",
                    "아침형", List.of("아침", "새벽", "일찍", "오전"),
                    List.of("아침에 일찍 일어나면 하루가 길어!", "상쾌한 아침형이 좋아요", "아침형 한 표요!", "아침 커피가 최고지"),
                    "아침형 한 표! 해랑 같이 일어나면 진짜 부지런해 보이더라고요~ 올빼미파 분들 어디 계세요?",
                    "아침형 승리! 해랑 같이 일어나는 부지런한 지구인들이 많네요!",
                    "올빼미형", List.of("밤", "올빼미", "야행성", "늦게", "새벽감성"),
                    List.of("무조건 올빼미형ㅋㅋ 밤에 집중 잘됨", "밤이 제일 재밌어 올빼미!", "올빼미형 여기요~", "별 보는 올빼미가 국룰이지"),
                    "올빼미형 한 표요! 솔라도 별이라 밤에 제일 쌩쌩해요! 동지 만났다~ 아침형 분들 힘내요!",
                    "올빼미형 승리! 별은 밤에 출근하니까 올빼미형 분들이랑 솔라랑 딱 맞아요!",
                    "솔라는 별이라 당연히 밤에 반짝이는 올빼미파에 한 표 주고 싶어요~",
                    "아침형이랑 올빼미형 반반! 그럼 솔라가 낮에도 밤에도 반짝여야겠네요!"),
            new BalanceGame("trip", "푸른 바다 vs 시원한 산",
                    List.of("바다", "여행", "산", "휴가"),
                    "오, 방금 {nick}님이 여행 얘기 꺼내 주셨네요! 휴가 간다면 푸른 바다 vs 시원한 산, 어디로 가고 싶어요? 투표 한번 해 볼까요!",
                    "지구 여행지 공부 중인데요! 파도 치는 푸른 바다 vs 나무 가득한 시원한 산, 여러분은 어디가 더 좋아요?",
                    "바다", List.of("바다", "파도", "해변", "물놀이"),
                    List.of("무조건 바다지 파도 소리 최고!", "바다 보면서 힐링하고 싶다", "바다 한 표요!!", "여름 바다 못 참지ㅋㅋ"),
                    "바다 한 표요! 밤바다에 별빛이 비치면 엄청 예쁘다면서요? 산파 분들도 어서 투표해요!",
                    "바다 승리! 밤바다에 별빛 비치는 거 솔라도 꼭 보고 싶어요!",
                    "산", List.of("산", "계곡", "숲", "등산", "캠핑"),
                    List.of("시원한 산이랑 계곡이 최고야", "산에서 별 보면 진짜 잘 보임!", "숲 향기 좋아서 산!", "산 캠핑 한 표요~"),
                    "산 한 표 추가요! 높은 산에선 솔라 고향 별이 더 잘 보인대요! 바다파 분들 가만히 있을 거예요?",
                    "산 승리! 높은 산에 올라가면 솔라 고향 별이 더 가까이 보인대요!",
                    "솔라는 별이 잘 보이는 산도 좋지만 파도 소리도 너무 궁금해요~",
                    "바다랑 산이 딱 동점! 지구엔 멋진 곳이 너무 많아서 부러워요!")
    );

    /** 솔라 주도 멀티 턴 미니 에피소드 (도입 → 시청자 반응 2회 콕 집어 티키타카 → 결말) */
    static final List<Episode> EPISODES = List.of(
            new Episode("편의점 자동문 사건",
                    "아 맞다! 오늘 방송 전에 지구 편의점에 몰래 가 봤거든요? 문이 저절로 스르륵 열려서 깜짝 놀랐어요! 여러분도 처음엔 신기하지 않았어요?",
                    List.of("자동문 보고 놀랐대ㅋㅋㅋ", "편의점 뭐 샀어 솔라야?", "알바생 깜짝 놀랐겠다ㅋㅋ", "편의점 털러 갔네ㅋㅋ", "자동문 마법이지ㅋㅋ"),
                    List.of(
                            new FollowUp(List.of("뭐 샀", "샀어", "털러"), "솔라는 별빛만 먹어서 구경만 했어요! 근데 삼각김밥이라는 게 세모나서 엄청 귀여웠어요!"),
                            new FollowUp(List.of("알바", "직원"), "맞아요, 직원분이 솔라를 엄청 신기하게 쳐다보시더라고요! 꼬리별 들킬 뻔했어요ㅋㅋ"),
                            new FollowUp(List.of("자동문", "놀랐", "마법", "신기"), "아니 손도 안 댔는데 스르륵 열리잖아요! 지구 기술 진짜 우주급 마법 같아요!")
                    ),
                    "다들 너무 태연하게 들어가시길래 솔라도 아는 척하고 쓱 들어가 봤어요!",
                    "그래서 너무 신기해서 문 앞에서 세 번이나 왔다 갔다 했잖아요! 다음엔 삼각김밥 껍질 까는 법도 배워 올게요!",
                    List.of("세 번 왔다갔다ㅋㅋㅋ", "귀여워ㅠㅠ", "삼각김밥 1번 2번 3번 당기면 돼!", "다음 썰도 기대할게ㅋㅋ")),
            new Episode("월요일 없는 별요일",
                    "여러분, 솔라 오늘 지구 달력 보고 진짜 충격 받았어요! 왜 월요일은 일주일마다 자꾸 돌아오는 거예요? 다들 월요일 좋아해요?",
                    List.of("ㄹㅇ 월요일 없애줘", "월요병 공감ㅋㅋㅋ", "솔라가 별빛으로 없애줘", "월요일 진짜 싫어ㅠ", "출근하기 싫다ㅠ"),
                    List.of(
                            new FollowUp(List.of("없애", "별빛"), "좋아요, 솔라가 오늘 밤 꼬리별 흔들어서 월요일만 살짝 지워 볼게요! 되면 대박이죠?"),
                            new FollowUp(List.of("출근", "회사", "학교", "월요병"), "아이고, 다들 월요일마다 싸우러 나가시는구나… 솔라가 일요일 밤마다 응원 방송 켜야겠어요!"),
                            new FollowUp(List.of("싫어", "공감", "월요일"), "역시 다들 같은 마음이네요! 달력 만든 사람한테 항의하러 가야겠어요!")
                    ),
                    "다들 월요일 얘기만 나와도 한숨 쉬시네요… 솔라가 별빛 에너지 나눠 드릴게요!",
                    "솔라 별엔 요일이 없어서 매일 주말이거든요! 다음엔 월요일 대신 다 같이 쉬는 '별요일' 만들면 안 될까요?",
                    List.of("별요일 찬성!!", "솔라 대통령 가자ㅋㅋ", "매일 주말 부럽다ㅠ", "별요일 제발 만들어줘ㅋㅋ")),
            new Episode("도도한 지구 고양이",
                    "아까 길 가다가 고양이 한 마리랑 눈이 마주쳤거든요? 솔라가 반짝 인사했는데 냐옹 한 번 하고 쌩 가 버렸어요! 원래 지구 고양이는 그래요?",
                    List.of("고양이 도도하네ㅋㅋ", "솔라 차였대ㅋㅋㅋ", "길냥이는 원래 그래ㅠ", "츄르 가져가야지ㅋㅋ", "고양이 정복 어렵지"),
                    List.of(
                            new FollowUp(List.of("츄르", "간식"), "오 '츄르'요? 그걸 흔들면 고양이가 달려온다고요? 바로 메모해 둘게요!"),
                            new FollowUp(List.of("차였", "무시"), "으악 차였다니! 솔라도 꼬리별 흔들면서 열심히 인사했단 말이에요!"),
                            new FollowUp(List.of("도도", "원래", "길냥"), "아 원래 그렇게 쿨한 친구들이구나! 괜히 솔라만 상처받을 뻔했네요ㅋㅋ")
                    ),
                    "근데 뒷모습 걸어가는 게 너무 몽실몽실해서 화도 못 냈어요!",
                    "다음엔 꼭 '츄르'라는 마법 간식을 들고 가서 지구 고양이랑 친구 먹어 볼래요! 성공하면 자랑할게요!",
                    List.of("츄르면 바로 친해짐ㅋㅋ", "솔라 파이팅ㅋㅋ", "고양이 정복 가자!", "후기 꼭 들려줘!")),
            new Episode("고향 별이 보인 밤",
                    "어젯밤에 하늘을 올려다보는데, 고향 별이 유독 반짝 보이더라고요! 잠깐 보고 싶어서 꼬리별 흔들어 줬어요. 여러분도 밤하늘 자주 봐요?",
                    List.of("솔라 고향 생각났구나ㅠ", "꼬리별 흔든거 귀여워", "지구에 계속 있어줘!", "어디 별인지 보고싶다", "밤하늘 예쁘지"),
                    List.of(
                            new FollowUp(List.of("계속", "있어줘", "가지마"), "걱정 마요! 아직 구경할 지구 음식도 백 가지나 남아서 당분간 절대 안 가요!"),
                            new FollowUp(List.of("어디", "보고싶", "밤하늘"), "오늘 밤 제일 밝게 깜빡이는 작은 별 보이면 그게 솔라 고향이에요! 꼭 찾아봐요!"),
                            new FollowUp(List.of("고향", "생각", "꼬리별"), "잠깐 그리웠는데, 오늘 방송 켜고 여러분 얼굴 보니까 금방 또 신났어요!")
                    ),
                    "밤하늘 보다가 반짝하는 별 있으면 솔라가 인사하는 줄 알아요!",
                    "여기 와서 여러분이랑 수다 떠는 게 너무 재밌어서요! 당분간 지구에 꾹 눌러앉아 보려고요! 반짝!",
                    List.of("평생 지구에 있어줘ㅠㅠ", "솔라 감동이다", "별빛 라디오 영원히!", "나도 솔라 덕분에 힘나")),
            new Episode("지구 노래 연습 중",
                    "솔라가 요즘 지구 노래 하나 연습 중인데요, 가사에 '사랑'이랑 '이별'이 진짜 많이 나오더라고요? 지구 노래는 원래 다 그래요?",
                    List.of("무슨 노래 연습중이야?", "한 소절만 불러줘!", "발라드 연습하나보다ㅋㅋ", "솔라 노래 기대됨!", "아이돌 노래도 해봐!"),
                    List.of(
                            new FollowUp(List.of("불러", "한 소절", "기대"), "에이 아직 음정이 별똥별처럼 삐끗해서요! 조금만 더 연습하고 멋지게 들려줄게요!"),
                            new FollowUp(List.of("무슨", "발라드", "아이돌"), "맞아요, 잔잔한 발라드랑 신나는 아이돌 노래 둘 다 따라 부르고 있어요!"),
                            new FollowUp(List.of("노래", "연습"), "별에선 노래 대신 반짝임으로 리듬을 타서, 가사 외우는 게 제일 신기해요!")
                    ),
                    "여러분 좋아하는 노래 있으면 채팅으로 추천해 줘요, 솔라가 다 들어 볼게요!",
                    "별빛 라디오에선 이별 말고 매일 다시 만나는 노래만 부르고 싶어요! 연습 더 해서 꼭 들려줄게요!",
                    List.of("기다릴게 솔라야!", "다시 만나는 노래 좋다ㅠ", "음정 삑사리도 귀여울듯ㅋㅋ", "솔라 콘서트 존버!"))
    );

    public static final long REASK_MS = 240_000;
    public static final int STORY_EVERY = 6;
    /** 방송 주도 모드에서는 3차례마다 솔라가 본인 에피소드로 티키타카를 연다 */
    public static final int HOST_EPISODE_EVERY = 3;

    public static final long OPENING_MS = 32_000;
    static final String HELLO_LINE = "안녕하세요 여러분! 솔라의 별빛 라디오 시작할게요! 반짝!";
    static final String AGENDA_LINE = "오늘은 솔라가 겪은 지구 이야기도 들려드리고, 재미있는 투표도 같이 해 볼게요! 편하게 수다 떨어요!";
    private long liveStartAt = Long.MIN_VALUE;
    private int helloStep;

    static final List<Story> STORIES = List.of(
            new Story("오늘 별똥별 타고 오다가 구름에 살짝 걸렸어요. 꼬리별이 아직 축축해요!",
                    List.of("구름에 걸렸대ㅋㅋㅋ", "꼬리별 말려줘야겠다", "축축한 별 귀엽네ㅋㅋ", "드라이기로 말려줘ㅋㅋ")),
            new Story("지구 사람들은 별 보면서 소원 빈다면서요? 솔라는 별인데… 누구한테 빌어야 해요?",
                    List.of("나한테 빌어 솔라야ㅋㅋ", "별이 소원 비는거 웃기네", "솔라 소원이 뭔데?", "지구인한테 빌어봐ㅋㅋ")),
            new Story("아까 처음으로 지구 바람을 만져 봤어요. 차갑고 간지러웠어요!",
                    List.of("바람 간지럽대ㅠㅠ귀여워", "요즘 바람 진짜 차갑지", "감기 조심해 솔라야", "별도 추위 타나?")),
            new Story("솔라 고향 별에선 인사 대신 반짝 두 번 해요. 여러분도 채팅으로 해 볼래요?",
                    List.of("반짝반짝!", "✨✨", "반짝 두 번 완료ㅋㅋ", "오 신기하다 반짝반짝")),
            new Story("지구 말 공부 중인데 '배고프다'가 제일 어려워요. 솔라는 배가 없거든요!",
                    List.of("배가 없대ㅋㅋㅋ", "다이어트 걱정 없어서 부럽다", "치킨 못 먹는건 아쉽네ㅠ", "별빛 맛 궁금하다")),
            new Story("채팅 보다가 깨달았어요. 지구엔 고양이 키우는 사람이 진짜 많네요?",
                    List.of("우리집 냥이 지금 자는중", "고양이가 지구 지배함ㅋㅋ", "솔라도 냥이 보면 반할듯", "강아지파도 있어요!")),
            new Story("솔라 꿈은 지구 음식 백 가지 구경하기예요. 오늘은 몇 개나 채웠을까요?",
                    List.of("100가지 금방 채우겠다ㅋㅋ", "치킨은 1번으로 적어놔", "떡볶이 무조건 넣어야지", "국밥도 구경해봐 솔라야")),
            new Story("별빛 라디오 오길 잘했다… 여러분 말 들으면 꼬리별이 막 따뜻해져요.",
                    List.of("솔라 감동받았어ㅠㅠ", "꼬리별 따뜻하대 귀여워", "나도 힐링된다", "오래 방송해줘 솔라야!")));

    private boolean showHostMode;

    private Question active;
    private long askedAt;
    private long lastAskAt = -ASK_GAP_MS;
    private int turnsSinceAsk;
    private int rotation;
    private final Map<String, Long> askedAt2 = new LinkedHashMap<>();
    private int turnsSinceStory;
    private int storyTurn;
    private Question topic;
    private long topicUntil;
    private boolean talkPending;
    private int talkCount;
    private long topicAskedAt;

    // ---- 밸런스 게임 상태 ----
    private BalanceGame activeBalance;
    private long balanceStartAt;
    private boolean balanceMidDone;
    private int balanceTurn;
    private final Map<String, Long> balanceUsedAt = new LinkedHashMap<>();
    private boolean preferBalanceNext = true;

    // ---- 2부작 에피소드 티키타카 상태 ----
    private Episode activeEpisode;
    private long episodeStartAt;
    private int episodeRepliesLeft;
    private int episodeTurn;

    // ---- 수동 코너 트리거 ----
    private String manualTrigger;

    public synchronized void setShowHostMode(boolean enabled) {
        this.showHostMode = enabled;
    }

    public synchronized void requestCorner(String corner) {
        this.manualTrigger = corner;
    }

    public synchronized void startLive(long nowMs, boolean greeted) {
        liveStartAt = nowMs;
        helloStep = greeted ? 1 : 0;
        lastAskAt = nowMs + OPENING_MS - ASK_GAP_MS;
        turnsSinceAsk = 0;
    }

    public synchronized boolean inOpening(long nowMs) {
        return liveStartAt != Long.MIN_VALUE && nowMs - liveStartAt < OPENING_MS;
    }

    public synchronized Plan next(long nowMs, Map<String, Integer> hot, boolean groupTurn) {
        if (activeBalance != null) {
            long elapsed = nowMs - balanceStartAt;
            if (elapsed >= BALANCE_MS) return new Plan(Kind.BALANCE_END, null, null, null);
            if (!balanceMidDone && elapsed >= BALANCE_MID_MS) {
                balanceMidDone = true;
                return new Plan(Kind.BALANCE_MID, null, null, null);
            }
            BalanceGame bg = activeBalance;
            long since = balanceStartAt;
            return new Plan(Kind.REACT, null, c -> c.receivedAtMs() >= since && bg.sideOf(c.text()) != null, null);
        }

        if (manualTrigger != null && active == null && !talkPending) {
            String trg = manualTrigger;
            manualTrigger = null;
            if ("balance".equals(trg)) {
                liveStartAt = Long.MIN_VALUE;
                activeEpisode = null;
                return new Plan(Kind.BALANCE_START, null, null, null);
            }
            if ("episode".equals(trg)) {
                liveStartAt = Long.MIN_VALUE;
                activeEpisode = null;
                return new Plan(Kind.EPISODE_START, null, null, null);
            }
        }

        if (liveStartAt != Long.MIN_VALUE && nowMs - liveStartAt < OPENING_MS) {
            if (helloStep == 0) {
                helloStep = 1;
                return new Plan(Kind.HELLO, null, null, HELLO_LINE);
            }
            if (helloStep == 1 && nowMs - liveStartAt >= OPENING_MS / 2) {
                helloStep = 2;
                return new Plan(Kind.HELLO, null, null, AGENDA_LINE);
            }
            return new Plan(Kind.REACT, null,
                    c -> LineWriter.detect(c.text(), c.gift()).topic() == LineWriter.Topic.GREETING, null);
        }
        if (active != null) {
            if (nowMs - askedAt >= ANSWER_MS) return new Plan(Kind.ANSWERS, active, null, null);
            Question q = active;
            long since = askedAt;
            return new Plan(Kind.REACT, q, c -> c.receivedAtMs() >= since && q.bucketOf(c.text()) != null, null);
        }
        if (talkPending && topic != null) {
            talkPending = false;
            return new Plan(Kind.TALK, topic, null, null);
        }

        // 솔라 에피소드 진행 중: 에피소드에 반응한 시청자 채팅만 콕 집어서 2번 티키타카 → 마무리!
        if (activeEpisode != null) {
            if (episodeRepliesLeft > 0) {
                episodeRepliesLeft--;
                Episode ep = activeEpisode;
                long since = episodeStartAt;
                return new Plan(Kind.REACT, null, c -> c.receivedAtMs() >= since && ep.matchesReaction(c.text()), null);
            }
            return new Plan(Kind.EPISODE_END, null, null, null);
        }

        turnsSinceAsk++;
        long cornerGap = showHostMode ? 45_000 : ASK_GAP_MS;
        int askTurns = showHostMode ? 5 : ASK_EVERY_TURNS;
        if (nowMs - lastAskAt >= cornerGap) {
            if (showHostMode && preferBalanceNext && turnsSinceAsk >= 3) {
                preferBalanceNext = false;
                return new Plan(Kind.BALANCE_START, null, null, null);
            }
            String hotLabel = hottest(hot);
            Question hotQ = hotLabel == null ? null : questionFor(hotLabel, nowMs);
            if (hotQ != null) {
                preferBalanceNext = true;
                return new Plan(Kind.ASK, hotQ, null, prefaceFor(hotLabel));
            }
            if (turnsSinceAsk >= askTurns) {
                Question q = nextUnasked(nowMs);
                if (q != null) {
                    preferBalanceNext = true;
                    return new Plan(Kind.ASK, q, null, null);
                }
            }
        }

        int storyEvery = showHostMode ? HOST_EPISODE_EVERY : STORY_EVERY;
        if (++turnsSinceStory >= storyEvery) {
            turnsSinceStory = 0;
            return new Plan(showHostMode ? Kind.EPISODE_START : Kind.STORY, null, null, null);
        }
        if (groupTurn) return new Plan(Kind.GROUP, null, null, null);
        if (topic != null && nowMs < topicUntil) {
            Question q = topic;
            return new Plan(Kind.REACT, q, c -> q.bucketOf(c.text()) != null, null);
        }
        return new Plan(Kind.REACT, null, null, null);
    }

    /** 밸런스 게임 시작 (최근 채팅 속 탕수육·민초·이불 등 떡밥과 자연스럽게 연결!) */
    public synchronized BalanceGame startBalance(long nowMs) {
        return startBalance(nowMs, List.of()).game();
    }

    public record StartedBalance(BalanceGame game, String introLine, String triggerNick) {
    }

    public synchronized StartedBalance startBalance(long nowMs, List<Chat> recent) {
        BalanceGame chosen = null;
        Chat triggerChat = null;
        // 최근 25초 채팅에서 아직 안 쓴 밸런스 게임 떡밥을 찾는다
        for (int i = recent.size() - 1; i >= 0 && chosen == null; i--) {
            Chat c = recent.get(i);
            if (nowMs - c.receivedAtMs() > 25_000) break;
            for (BalanceGame bg : BALANCE_GAMES) {
                Long used = balanceUsedAt.get(bg.id());
                if (used != null && nowMs - used < REASK_MS) continue;
                if (bg.triggeredBy(c.text())) {
                    chosen = bg;
                    triggerChat = c;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = BALANCE_GAMES.get(balanceTurn++ % BALANCE_GAMES.size());
        }
        activeBalance = chosen;
        balanceUsedAt.put(chosen.id(), nowMs);
        balanceStartAt = nowMs;
        balanceMidDone = false;
        lastAskAt = nowMs;
        turnsSinceAsk = 0;
        turnsSinceStory = 0;
        String introLine = triggerChat != null
                ? chosen.segueIntro().replace("{nick}", triggerChat.nickname())
                : chosen.intro();
        return new StartedBalance(chosen, introLine, triggerChat == null ? null : triggerChat.nickname());
    }

    public synchronized BalanceView balanceProgress(List<Chat> recent, long nowMs) {
        if (activeBalance == null) return null;
        Map<String, String> votes = collectBalanceVotes(activeBalance, balanceStartAt, recent);
        int a = 0, b = 0;
        for (String side : votes.values()) {
            if ("A".equals(side)) a++;
            else b++;
        }
        return new BalanceView(activeBalance.title(), activeBalance.optionA(), activeBalance.optionB(),
                a, b, Math.max(0, BALANCE_MS - (nowMs - balanceStartAt)));
    }

    /** 밸런스 게임 투표 중 시청자 의견에 솔라 본인 취향·유도를 섞어 티키타카 답한다 */
    public synchronized String balanceChatReply(Chat chat) {
        BalanceGame bg = activeBalance;
        if (bg == null) return null;
        String side = bg.sideOf(chat.text());
        if (side == null) return null;
        String quote = chat.text().length() <= 16 ? "\"" + chat.text().strip() + "\" " : chat.nickname() + "님, ";
        return quote + ("A".equals(side) ? bg.banterA() : bg.banterB());
    }

    public synchronized String balanceMidLine(List<Chat> recent) {
        BalanceGame bg = activeBalance;
        if (bg == null) return null;
        Map<String, String> votes = collectBalanceVotes(bg, balanceStartAt, recent);
        int a = 0, b = 0;
        for (String s : votes.values()) {
            if ("A".equals(s)) a++;
            else b++;
        }
        if (a + b == 0) return bg.title() + " 투표 진행 중이에요! " + bg.midFlavor() + " 채팅으로 골라 줘요!";
        if (Math.abs(a - b) <= 2) {
            return "지금 " + bg.optionA() + " " + a + "표, " + bg.optionB() + " " + b + "표로 완전 박빙이에요! " + bg.midFlavor();
        }
        String lead = a > b ? bg.optionA() : bg.optionB();
        String trail = a > b ? bg.optionB() : bg.optionA();
        return "중간 판세! " + bg.optionA() + " " + a + "표 대 " + bg.optionB() + " " + b + "표로 " + lead + "이 앞서고 있어요! " + trail + "파 분들 역전 가능할까요?";
    }

    public synchronized BalanceResult finishBalance(List<Chat> recent, long nowMs) {
        BalanceGame bg = activeBalance;
        Map<String, String> votes = collectBalanceVotes(bg, balanceStartAt, recent);
        int a = 0, b = 0;
        for (String s : votes.values()) {
            if ("A".equals(s)) a++;
            else b++;
        }
        activeBalance = null;
        lastAskAt = nowMs;
        String verdict;
        if (a + b == 0) {
            verdict = "투표 마감! 어, 다들 고민 중이었나 봐요! 다음엔 꼭 골라 줘요~";
        } else if (a > b) {
            verdict = "투표 마감! " + bg.optionA() + " " + a + "표 대 " + bg.optionB() + " " + b + "표! " + bg.winA();
        } else if (b > a) {
            verdict = "투표 마감! " + bg.optionB() + " " + b + "표 대 " + bg.optionA() + " " + a + "표! " + bg.winB();
        } else {
            verdict = "투표 마감! " + bg.optionA() + " " + a + "표, " + bg.optionB() + " " + b + "표! " + bg.tieLine();
        }
        return new BalanceResult(bg, a, b, List.copyOf(votes.keySet()), verdict);
    }

    private static Map<String, String> collectBalanceVotes(BalanceGame bg, long since, List<Chat> recent) {
        Map<String, String> votes = new LinkedHashMap<>();
        for (Chat c : recent) {
            if (c.receivedAtMs() < since) continue;
            String side = bg.sideOf(c.text());
            if (side != null) votes.put(c.viewerId(), side);
        }
        return votes;
    }

    public synchronized Episode startEpisode() {
        return startEpisode(System.currentTimeMillis());
    }

    public synchronized Episode startEpisode(long nowMs) {
        Episode ep = EPISODES.get(episodeTurn++ % EPISODES.size());
        activeEpisode = ep;
        episodeStartAt = nowMs;
        episodeRepliesLeft = 2; // 시청자 반응 2번 티키타카 주고받고 마무리!
        return ep;
    }

    public synchronized Episode activeEpisode() {
        return activeEpisode;
    }

    public synchronized Episode finishEpisode() {
        Episode ep = activeEpisode;
        activeEpisode = null;
        return ep;
    }

    public synchronized BalanceGame activeBalance() {
        return activeBalance;
    }

    public synchronized String startAsk(Question q, String preface, long nowMs) {
        active = q;
        askedAt2.put(q.id(), nowMs);
        turnsSinceStory = 0;
        askedAt = nowMs;
        lastAskAt = nowMs;
        turnsSinceAsk = 0;
        topic = q;
        topicUntil = Long.MAX_VALUE;
        return (preface == null ? "" : preface) + q.ask();
    }

    public synchronized Answers finishAsk(List<Chat> recent, Set<String> voiceCalled, long nowMs) {
        Question q = active;
        Map<String, String> lastBucket = new LinkedHashMap<>();
        Map<String, String> nick = new LinkedHashMap<>();
        for (Chat c : recent) {
            if (c.receivedAtMs() < askedAt) continue;
            String b = q.bucketOf(c.text());
            if (b == null) continue;
            lastBucket.remove(c.viewerId());
            lastBucket.put(c.viewerId(), b);
            nick.put(c.viewerId(), c.nickname());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String b : lastBucket.values()) counts.merge(b, 1, Integer::sum);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        String highlight = null;
        for (String id : lastBucket.keySet()) {
            if (!voiceCalled.contains(id)) {
                highlight = nick.get(id);
                break;
            }
        }
        if (highlight == null && !nick.isEmpty()) highlight = nick.values().iterator().next();

        active = null;
        topicUntil = nowMs + TOPIC_MS;
        topicAskedAt = askedAt;
        talkPending = !lastBucket.isEmpty();
        return new Answers(q, counts, lastBucket.size(), List.copyOf(lastBucket.keySet()), highlight,
                answersLine(q, sorted, highlight));
    }

    static String answersLine(Question q, List<Map.Entry<String, Integer>> sorted, String highlight) {
        if (sorted.isEmpty()) return "어, 아무도 대답 안 했다… 솔라 혼자 떠든 거예요? 히히.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getKey()).append(' ').append(sorted.get(i).getValue()).append("명");
        }
        sb.append("! ");
        if (highlight != null) sb.append(highlight).append("님 답 고마워요. ");
        sb.append(q.comment());
        return sb.toString();
    }

    public synchronized Talk talkMaterial(List<Chat> recent, Set<String> said) {
        Question q = topic;
        Map<String, Chat> byBucket = new LinkedHashMap<>();
        for (int pass = 0; pass < 2 && byBucket.size() < 2; pass++) {
            for (int i = recent.size() - 1; i >= 0 && byBucket.size() < 2; i--) {
                Chat c = recent.get(i);
                if (c.receivedAtMs() < topicAskedAt) continue;
                String b = q.bucketOf(c.text());
                if (b == null || byBucket.containsKey(b)) continue;
                if (pass == 0 && said.contains(c.viewerId())) continue;
                if (byBucket.values().stream().anyMatch(x -> x.viewerId().equals(c.viewerId()))) continue;
                byBucket.put(b, c);
            }
        }
        List<Chat> chats = List.copyOf(byBucket.values());
        return new Talk(q, chats, talkTemplate(q, new ArrayList<>(byBucket.keySet()), chats, talkCount++));
    }

    static String rang(String word) {
        char c = word.charAt(word.length() - 1);
        boolean batchim = c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
        return batchim ? "이랑" : "랑";
    }

    static String talkTemplate(Question q, List<String> buckets, List<Chat> chats) {
        return talkTemplate(q, buckets, chats, 0);
    }

    static String talkTemplate(Question q, List<String> buckets, List<Chat> chats, int variant) {
        if (chats.isEmpty()) return "다들 답해 줘서 고마워요! 솔라 오늘 지구 공부 많이 했어요.";
        String n1 = chats.get(0).nickname(), b1 = buckets.get(0);
        if (chats.size() == 1) return n1 + "님은 " + b1 + "! " + q.comment();
        String n2 = chats.get(1).nickname(), b2 = buckets.get(1);
        int v = Math.floorMod(variant, 3);
        if (v == 1) return switch (q.id()) {
            case "dinner" -> n1 + "님 " + b1 + ", " + n2 + "님 " + b2 + "! 둘 다 맛있겠다, 다른 분들은 뭐 먹었어요?";
            case "game" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 둘이 같이 하면 재밌겠다!";
            case "mood" -> n1 + "님, " + n2 + "님 둘 다 오늘 하루 잘 버텼어요. 솔라가 별빛 박수 보낼게요!";
            default -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 다른 분들은 어때요?";
        };
        if (v == 2) return switch (q.id()) {
            case "dinner" -> b1 + rang(b1) + " " + b2 + "! " + n1 + "님, " + n2 + "님 덕분에 솔라 지구 메뉴 공부 끝!";
            case "game" -> b1 + "파 " + n1 + "님, " + b2 + "파 " + n2 + "님! 솔라는 옆에서 응원 담당 할게요.";
            case "mood" -> b1.replace(" 분", "") + " " + n1 + "님도, " + b2.replace(" 분", "") + " " + n2 + "님도 오늘은 솔라랑 같이 쉬어 가요.";
            default -> b1 + rang(b1) + " " + b2 + "! " + n1 + "님, " + n2 + "님 덕분에 솔라 지구 공부했네요.";
        };
        return switch (q.id()) {
            case "dinner" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 솔라 별엔 음식이 없어서 다 궁금해요.";
            case "game" -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! 솔라는 구경만 해도 두근두근해요.";
            case "mood" -> n1 + "님처럼 " + b1.replace(" 분", "") + " 날도, " + n2 + "님처럼 " + b2.replace(" 분", "") + " 날도 있죠. 솔라가 옆에서 반짝일게요.";
            default -> n1 + "님은 " + b1 + ", " + n2 + "님은 " + b2 + "! " + q.comment();
        };
    }

    public synchronized Story nextStory() {
        return STORIES.get(storyTurn++ % STORIES.size());
    }

    public synchronized String storyLine() {
        return nextStory().line();
    }

    public synchronized Status status(long nowMs) {
        return status(nowMs, List.of());
    }

    public synchronized Status status(long nowMs, List<Chat> recent) {
        if (activeBalance != null) {
            BalanceView bv = balanceProgress(recent, nowMs);
            return new Status("밸런스 게임: " + activeBalance.title(), true, bv.remainingMs(),
                    activeBalance.title() + " — 어느 쪽에 투표할까요?",
                    List.of(activeBalance.optionA(), activeBalance.optionB()), bv);
        }
        if (inOpening(nowMs)) return new Status("방송 시작 인사", false, OPENING_MS - (nowMs - liveStartAt), null, List.of());
        if (active != null) return new Status(active.label(), true, Math.max(0, ANSWER_MS - (nowMs - askedAt)), active.ask(), active.quickReplies());
        if (activeEpisode != null) return new Status("솔라 토크: " + activeEpisode.title(), false, 0, null, List.of());
        if (topic != null && nowMs < topicUntil) return new Status(topic.label(), false, topicUntil - nowMs, null, List.of());
        return new Status(null, false, 0, null, List.of());
    }

    /** 떡밥·밸런스 게임·에피소드 티키타카 중에는 후원 읽기를 보류한다 */
    public synchronized boolean inSegment() {
        return active != null || talkPending || activeBalance != null || activeEpisode != null;
    }

    public synchronized Question active() {
        return active;
    }

    private boolean available(Question q, long nowMs) {
        Long t = askedAt2.get(q.id());
        return t == null || nowMs - t >= REASK_MS;
    }

    private Question nextUnasked(long nowMs) {
        for (int i = 0; i < ROTATION.size(); i++) {
            Question q = ROTATION.get(rotation++ % ROTATION.size());
            if (available(q, nowMs)) return q;
        }
        return null;
    }

    Question questionFor(String hotLabel, long nowMs) {
        List<Question> candidates = LineWriter.FOOD_KW.contains(hotLabel) ? List.of(DINNER, SNACK)
                : LineWriter.GAME_KW.contains(hotLabel) ? List.of(GAME, WEEKEND)
                : hotLabel.equals("피곤") ? List.of(MOOD, WEEKEND) : List.of(DINNER);
        for (Question q : candidates) if (available(q, nowMs)) return q;
        return null;
    }

    private static String hottest(Map<String, Integer> hot) {
        String best = null;
        for (Map.Entry<String, Integer> e : hot.entrySet()) {
            if (e.getKey().equals("인사")) continue;
            if (e.getValue() >= HOT_MIN && (best == null || e.getValue() > hot.get(best))) best = e.getKey();
        }
        return best;
    }

    static String prefaceFor(String hotLabel) {
        if (hotLabel.equals("피곤")) return "오늘 피곤한 분이 많네요. ";
        return "지금 " + hotLabel + " 얘기 많다! ";
    }

    private static Map<String, List<String>> buckets(List<String> keywords) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String k : keywords) m.put(k, List.of(k));
        return m;
    }

    private static Map<String, List<String>> orderedBuckets(String[][] rows) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String[] r : rows) {
            Set<String> kws = new LinkedHashSet<>(List.of(r).subList(1, r.length));
            m.put(r[0], List.copyOf(kws));
        }
        return m;
    }
}
