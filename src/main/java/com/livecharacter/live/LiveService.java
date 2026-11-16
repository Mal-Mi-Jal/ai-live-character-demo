package com.livecharacter.live;

import com.livecharacter.engine.Chat;
import com.livecharacter.engine.Director;
import com.livecharacter.engine.DonationQueue;
import com.livecharacter.engine.LiveRoom;
import com.livecharacter.engine.PickRule;
import com.livecharacter.llm.HaikuLineWriter;
import com.livecharacter.sim.BotAudience;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 순수 Java 엔진(LiveRoom, BotAudience)을 실제 시계에 물려 돌리고, 결과를 STOMP로 내보낸다.
 * 스프링 비유: 엔진 = 도메인 서비스, 이 클래스 = 스케줄러 + 메시지 발행 어댑터.
 *
 * 음성 기준 진행: 캐릭터 입은 하나라서 "한 명 고르기 → 대사 만들기 → 그 대사를 읽는 시간만큼 기다리기 → 다음 차례".
 *  - 목소리로 이름을 부르는 건 한 차례에 1명, 같은 순서로 다음 몇 명은 화면에 "봤어요 ❤"로만 띄운다
 *  - groupEvery 차례마다 한 번은 같은 이야기를 한 여러 명을 한 줄로 알아본다 (묶음 인정)
 *  - 읽는 시간 = 글자 수 / 초당 글자 수 (Windows 한국어 음성 실측 5.7자)
 */
@Service
public class LiveService {

    public record ChatView(long seq, String nickname, String text, int gift, boolean human) {
    }

    public record StateView(String rule, String ruleLabel, int bots, int screenK, int groupEvery,
                            List<RuleView> rules, LiveRoom.Stats stats, LlmView llm, VoiceView voice,
                            LiveRoom.ReturnStats returns, LiveRoom.LiveSummary lastLive) {
    }

    /** LLM 대사 현황: 켜졌나, 몇 번 불렀나, 얼마나 걸렸나, 검사에 몇 줄 걸렸나 */
    public record LlmView(boolean enabled, int callsUsed, int maxCalls, long llmP50Ms, long llmP95Ms,
                          long llmLines, long templateLines, Map<String, Long> rejected,
                          long inputTokens, long outputTokens) {
    }

    /** 음성 진행 숫자: 몇 차례 말했나, 1분에 몇 번, 채팅 → 목소리 시작까지 */
    public record VoiceView(int turns, double turnsPerMin, long startP50Ms, long startP95Ms, int groupTurns) {
    }

    public record RuleView(String id, String label) {
    }

    /** 후원 읽기 차례. readText = 시스템 목소리(후원 알림), thanks = 캐릭터 감사 인사 */
    public record DonationSpeak(int turn, String kind, List<DonationQueue.Donation> items, String readText,
                                String thanks, long speakMs, DonationQueue.Stats stats,
                                List<DonationQueue.Donation> queue, Director.Status topic) {
    }

    /** 후원 대기열 화면용 (새 후원이 들어올 때마다) */
    public record DonationBoard(List<DonationQueue.Donation> queue, DonationQueue.Stats stats) {
    }

    /**
     * 한 차례 말하기. kind = "fan"(한 명 이름 부르기) 또는 "group"(묶음 인정).
     * speakMs = 이 대사를 읽는 데 걸리는 시간 (서버가 다음 차례를 이만큼 뒤에 연다)
     */
    public record SpeakView(int turn, String kind, LiveRoom.Reaction reaction, LiveRoom.GroupMoment group,
                            List<LiveRoom.Ack> screenAcks, long speakMs, long llmMs, LiveRoom.Stats stats,
                            String ruleLabel, int bots, LlmView llm, VoiceView voice, LiveRoom.ReturnStats returns,
                            Director.Status topic) {
    }

    private static final Set<Integer> ALLOWED_BOTS = Set.of(0, 100, 300, 1000);
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    /** 말할 사람이 없을 때 다시 볼 간격 */
    private static final long IDLE_MS = 1000;
    /** 대사와 대사 사이 숨 고르기 */
    private static final long GAP_MS = 400;

    /**
     * 대사와 대사 사이 정적(채팅을 읽는 시간). 사용자 피드백(2026-11-17): 쉬지 않고 계속 말하니 어색하다.
     * 종류별로 다르게: 질문 뒤엔 답을 기다리며 길게, 이야기 뒤엔 여운, 짧은 반응 뒤엔 짧게. 길이는 조금씩 흔들어 기계적이지 않게
     */
    static long pauseAfter(String kind) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        return switch (kind) {
            case "ask" -> r.nextLong(3000, 5000);
            case "answer" -> r.nextLong(1000, 2000);
            case "talk", "story", "answers", "opening" -> r.nextLong(2000, 4000);
            default -> r.nextLong(1500, 3500);
        };
    }

    private final SimpMessagingTemplate template;
    private final HaikuLineWriter llm;
    private final long llmTimeoutMs;
    private final double charsPerSec;
    private final int screenK;
    private final int groupEvery;
    private final int chatFlushMs;
    private final int botStepMs;
    private final long seed;

    // LLM·음성 숫자 (여러 스레드에서 갱신하므로 이 객체로 동기화)
    private final Object statsLock = new Object();
    private final List<Long> llmTimes = new ArrayList<>();
    private final Map<String, Long> rejected = new LinkedHashMap<>();
    private long llmLines, templateLines, inTokens, outTokens;
    private final List<Long> voiceStarts = new ArrayList<>();
    /** 최근에 말한 대사 (이어 말하기에서 같은 표현을 되풀이하지 않게 LLM에 넘긴다) */
    private final java.util.ArrayDeque<String> recentLines = new java.util.ArrayDeque<>();
    /** 최근 이어 말하기 대사 (이어 말하기는 1분에 한 번쯤이라 위 5줄에는 거의 안 남는다 → 따로 기억) */
    private final java.util.ArrayDeque<String> recentTalks = new java.util.ArrayDeque<>();
    private int turns, groupTurns;
    private long voiceSince = System.currentTimeMillis();

    private final LiveRoom room;
    private volatile BotAudience audience;
    private volatile PickRule rule = PickRule.FAIR;
    private volatile LiveRoom.LiveSummary lastLive;
    /** 다음 차례에 무엇을 할지 정하는 진행자 (떡밥 → 질문 → 답 모으기 → 정리 → 주제 이어 가기) */
    private volatile Director director = new Director();
    /** 후원 대기열 (라이브마다 새로) */
    private volatile DonationQueue donations = new DonationQueue();
    /** 채팅창에 띄울 후원 채팅 (후원은 방의 일반 채팅 후보로 넣지 않는다 — 감사는 대기열이 맡음) */
    private final List<ChatView> donationChats = new ArrayList<>();
    private boolean donationsChanged;
    private long donationSeq;
    /** 마지막 후원 차례 뒤로 지난 일반 차례 수. 후원은 3차례에 한 번만 (사용자 피드백 2026-11-17: 후원만 읽고 채팅은 안 읽는다) */
    private volatile int turnsSinceDonation = 99;
    private static final int DONATION_EVERY = 3;
    private final double botGiftScale;
    /** 다음 라이브 첫 차례에 말할 오프닝 (지난 라이브 요약) */
    private volatile String pendingOpening;
    private ScheduledExecutorService scheduler;

    public LiveService(SimpMessagingTemplate template, HaikuLineWriter llm,
                       @Value("${live.llm-timeout-ms}") long llmTimeoutMs,
                       @Value("${live.voice-chars-per-sec}") double charsPerSec,
                       @Value("${live.screen-acks}") int screenK,
                       @Value("${live.group-every}") int groupEvery,
                       @Value("${live.voice-max-age-ms}") long voiceMaxAgeMs,
                       @Value("${live.bot-gift-scale}") double botGiftScale,
                       @Value("${live.bots}") int bots,
                       @Value("${live.chat-flush-ms}") int chatFlushMs,
                       @Value("${live.bot-step-ms}") int botStepMs,
                       @Value("${live.seed}") long seed) {
        this.template = template;
        this.llm = llm;
        this.llmTimeoutMs = llmTimeoutMs;
        this.charsPerSec = charsPerSec;
        this.screenK = screenK;
        this.groupEvery = groupEvery;
        this.chatFlushMs = chatFlushMs;
        this.botStepMs = botStepMs;
        this.seed = seed;
        this.room = new LiveRoom(rule, 1, screenK, voiceMaxAgeMs, seed);
        room.setEchoStyle(true); // 화면 데모: 이름 부르기와 채팅 되읽기를 섞는다
        this.botGiftScale = botGiftScale;
        this.audience = new BotAudience(bots, seed, botGiftScale);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "live-loop"));
        scheduler.scheduleAtFixedRate(guard(this::botStep), botStepMs, botStepMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(guard(this::flushChat), chatFlushMs, chatFlushMs, TimeUnit.MILLISECONDS);
        director.startLive(System.currentTimeMillis() + 3000, false);
        scheduleTurn(3000);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** 예약 작업에서 예외가 한 번 나면 그 작업은 조용히 멈춘다 → 잡아서 로그만 남기고 계속 돈다 */
    private static Runnable guard(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                System.err.println("[live-loop] " + e);
            }
        };
    }

    private void scheduleTurn(long delayMs) {
        if (scheduler.isShutdown()) return;
        scheduler.schedule(() -> {
            try {
                turn();
            } catch (RuntimeException e) {
                System.err.println("[live-loop] " + e);
                scheduleTurn(IDLE_MS); // 한 번 실패해도 진행이 멈추지 않게
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void botStep() {
        long now = System.currentTimeMillis();
        for (BotAudience.Sent s : audience.step(botStepMs)) {
            if (s.gift() > 0) donate(s.bot().id(), s.bot().nickname(), s.gift(), s.text(), false, now);
            else room.receive(s.bot().id(), s.bot().nickname(), s.text(), 0, now, false);
        }
    }

    /** 후원: 대기열에 넣고, 채팅창에도 강조해서 띄운다 */
    private void donate(String viewerId, String nickname, int amount, String text, boolean human, long now) {
        DonationQueue.Donation d = donations.offer(viewerId, nickname, amount, text, human, now);
        synchronized (donationChats) {
            donationChats.add(new ChatView(-(++donationSeq), d.nickname(), d.message() == null ? "" : d.message(), amount, human));
            donationsChanged = true;
        }
    }

    public void humanDonate(String viewerId, String nickname, Integer amount, String text) {
        if (viewerId == null || !VIEWER_ID.matcher(viewerId).matches() || amount == null
                || !Set.of(1000, 5000, 10000).contains(amount)) return;
        String nick = nickname == null || nickname.isBlank() ? "나" : nickname.strip();
        if (nick.length() > 12) nick = nick.substring(0, 12);
        donate("me-" + viewerId, nick, amount, text == null ? "" : text, true, System.currentTimeMillis());
    }

    private void flushChat() {
        List<ChatView> extra;
        boolean changed;
        synchronized (donationChats) {
            extra = List.copyOf(donationChats);
            donationChats.clear();
            changed = donationsChanged;
            donationsChanged = false;
        }
        if (changed) template.convertAndSend("/topic/donations", new DonationBoard(donations.snapshot(), donations.stats()));
        List<Chat> chats = room.takeUnflushed();
        if (chats.isEmpty() && extra.isEmpty()) return;
        // 화면에는 최근 것만 보이면 된다. 1000명일 때 0.5초 묶음이 수십 개라도 한 메시지로 간다
        List<ChatView> view = chats.stream()
                .skip(Math.max(0, chats.size() - 60))
                .map(c -> new ChatView(c.seq(), c.nickname(), c.text(), c.gift(), c.human()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        view.addAll(extra);
        template.convertAndSend("/topic/chat", view);
    }

    long speakMs(String line) {
        return Math.round(line.length() / charsPerSec * 1000) + GAP_MS;
    }

    /** 한 차례: 묶음 인정 차례면 묶음, 아니면 한 명 + 화면 인정. 다 말하고 나면 다음 차례를 예약한다 */
    private void turn() {
        long now = System.currentTimeMillis();
        String ruleLabel = rule.label();
        int bots = audience.bots().size();
        if (switchPending) {
            // 마무리 인사를 마쳤으면 여기서 다음 라이브로 바꾼다 (차례 번호도 새로 센다)
            switchPending = false;
            switchLive();
        }
        int turnNo;
        synchronized (statsLock) {
            turnNo = turns + 1;
        }
        if (endRequested) {
            endRequested = false;
            switchPending = true;
            int fans = room.stats().chatters();
            String closing = "오늘 방송은 여기까지! 와 준 " + fans + "명 다 고마워요. 다음 라이브에서 또 만나요!";
            speakSpecial(turnNo, "closing", new LiveRoom.GroupMoment("마무리", fans, List.of(), closing), ruleLabel, bots);
            return;
        }
        String opening = pendingOpening;
        if (opening != null) {
            pendingOpening = null;
            long ms = speakMs(opening);
            cheerFor("opening");
            synchronized (statsLock) {
                turns++;
            }
            LiveRoom.LiveSummary s = lastLive;
            LiveRoom.GroupMoment g = new LiveRoom.GroupMoment(s.topLabel() == null ? "지난 라이브" : s.topLabel(),
                    s.topCount(), List.of(), opening);
            template.convertAndSend("/topic/speak", new SpeakView(turnNo, "opening", null, g, List.of(),
                    ms, 0, room.stats(), ruleLabel, bots, llmView(), voiceView(), room.returnStats(), director.status(System.currentTimeMillis())));
            scheduleTurn(ms + pauseAfter("opening"));
            return;
        }
        Director d = director;
        // 후원: 떡밥 구간이 아니면 한 차례씩 번갈아 읽는다 (일반 채팅 차례를 다 빼앗지 않게).
        // 떡밥 구간 중이면 보류, 너무 쌓이면 메시지를 건너뛰고 한 줄로 묶어 감사
        DonationQueue.Plan dp = donations.next(now, d.inSegment(), turnsSinceDonation >= DONATION_EVERY - 1);
        if (dp.kind() != DonationQueue.Kind.NONE) {
            turnsSinceDonation = 0;
            speakDonation(turnNo, dp);
            return;
        }
        turnsSinceDonation++;
        boolean groupTurn = groupEvery > 0 && turnNo % groupEvery == 0;
        Director.Plan plan = d.next(now, room.hotTopics(now), groupTurn);

        if (plan.kind() == Director.Kind.ASK) {
            String line = d.startAsk(plan.question(), plan.preface(), now);
            audience.answering(plan.question().botAnswers()); // 데모 봇도 질문에 답하게
            speakSpecial(turnNo, "ask", new LiveRoom.GroupMoment(plan.question().label(), 0, List.of(), line), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.ANSWERS) {
            Director.Answers a = d.finishAsk(room.recentChats(), room.voiceCalled(), now);
            audience.answering(null);
            room.ackAnswers(a.answerers()); // 답한 팬은 "답 모아 반응"으로 함께 인정
            String label = a.counts().entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                    .reduce((x, y) -> x + " · " + y).orElse("답 없음");
            speakSpecial(turnNo, "answers", new LiveRoom.GroupMoment(label, a.total(), List.of(), a.line()), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.TALK) {
            Director.Talk talk = d.talkMaterial(room.recentChats(), room.voiceCalled());
            java.util.Set<String> others = new java.util.HashSet<>();
            for (Chat c : room.recentChats()) others.add(c.nickname());
            List<String> recent, talks;
            synchronized (statsLock) {
                recent = List.copyOf(recentLines);
                talks = List.copyOf(recentTalks);
            }
            // LLM은 잠금 밖, 스케줄러 스레드 밖. 늦으면 템플릿
            llm.talk(talk, recent, others, talks)
                    .completeOnTimeout(new HaikuLineWriter.TalkResult(talk.templateLine(), com.livecharacter.engine.LineWriter.Emotion.HAPPY,
                            "template", true, llmTimeoutMs, "시간 초과", null, 0, 0), llmTimeoutMs, TimeUnit.MILLISECONDS)
                    .thenAccept(res -> {
                        recordTalk(res);
                        synchronized (statsLock) {
                            recentTalks.addLast(res.line());
                            while (recentTalks.size() > 5) recentTalks.pollFirst();
                        }
                        String label = (res.source().equals("llm") ? "Haiku" : "템플릿") + " · " + String.join(", ", talk.nicknames());
                        speakSpecial(turnNo, "talk", new LiveRoom.GroupMoment(label, talk.chats().size(), talk.nicknames(), res.line()),
                                ruleLabel, bots);
                    });
            return;
        }
        if (plan.kind() == Director.Kind.HELLO) {
            speakSpecial(turnNo, "hello", new LiveRoom.GroupMoment("인사", 0, List.of(), plan.preface()), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.STORY) {
            // 솔라가 먼저 자기 이야기 한 줄 (템플릿, 설정표 바탕)
            String line = d.storyLine();
            speakSpecial(turnNo, "story", new LiveRoom.GroupMoment("솔라 이야기", 0, List.of(), line), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.GROUP) {
            // 인사 묶음은 빼고, 같은 주제는 3분 안에 다시 묶지 않는다 (같은 말 되풀이로 정적이던 문제)
            LiveRoom.GroupMoment g = room.groupMoment(now, 180_000, true);
            if (g != null) {
                synchronized (statsLock) {
                    groupTurns++;
                }
                speakSpecial(turnNo, "group", g, ruleLabel, bots);
                return;
            }
        }
        LiveRoom.TickResult r = room.tick(now, plan.prefer());
        if (r.reactions().isEmpty()) {
            scheduleTurn(IDLE_MS);
            return;
        }
        List<LiveRoom.Reaction> reactions = answerLines(r.reactions(), d.active());
        // LLM은 방 잠금 밖, 스케줄러 스레드 밖에서 부른다. 늦으면 템플릿 대사가 나간다
        llm.rewrite(reactions)
                .completeOnTimeout(new HaikuLineWriter.Result(reactions, true, llmTimeoutMs,
                        List.of("시간 초과"), 0, 0), llmTimeoutMs, TimeUnit.MILLISECONDS)
                .thenAccept(res -> {
                    LiveRoom.Reaction x = res.reactions().get(0);
                    long llmMs = res.called() ? res.llmMs() : 0;
                    long ms = speakMs(x.line());
                    synchronized (statsLock) {
                        turns++;
                        remember(x.line());
                        // 채팅 → 목소리 시작 = 고르기까지 기다린 시간 + 대사 만드는 시간
                        voiceStarts.add(x.waitMs() + (System.currentTimeMillis() - now));
                    }
                    record(res);
                    // 질문 답을 모으는 중이면 정적을 짧게 해서 답을 여러 개 읽어 준다
                    long pause = pauseAfter(director.active() != null ? "answer" : "fan");
                    template.convertAndSend("/topic/speak", new SpeakView(turnNo, "fan", x, null, r.screenAcks(),
                            ms, llmMs, r.stats(), ruleLabel, bots, llmView(), voiceView(), room.returnStats(), director.status(System.currentTimeMillis())));
                    scheduleTurn(ms + pause);
                });
    }

    private static final List<String> CHEER_HELLO = List.of("안녕 솔라!", "하이하이", "솔라 왔다!", "오늘도 반가워요", "ㅎㅇㅎㅇ", "처음 왔어요!");
    private static final List<String> CHEER_REACT = List.of("ㅋㅋㅋㅋ", "귀여워", "솔라 최고", "ㅠㅠ 공감", "와 진짜?", "ㅋㅋㅋ 솔라답다");
    private static final List<String> CHEER_BIG = List.of("오 만원!", "큰손 등장", "ㄷㄷ 만원", "멋지다");
    private static final List<String> CHEER_BYE = List.of("수고했어요!", "잘 자 솔라", "다음에 또 올게요", "오늘 재밌었어요", "빠이빠이");

    /** 방송 흐름에 맞춰 봇 채팅이 잠깐 따라오게 한다 (화면 데모용) */
    private void cheerFor(String kind) {
        switch (kind) {
            case "hello", "opening" -> audience.cheer(CHEER_HELLO, 0.5, 25_000);
            case "story", "talk", "answers" -> audience.cheer(CHEER_REACT, 0.35, 8_000);
            case "closing" -> audience.cheer(CHEER_BYE, 0.6, 15_000);
            default -> {
            }
        }
    }

    private void speakDonation(int turnNo, DonationQueue.Plan dp) {
        if (dp.items().stream().anyMatch(x -> x.amount() >= DonationQueue.BIG_AT)) audience.cheer(CHEER_BIG, 0.3, 6_000);
        long ms = (dp.readText() == null ? 0 : speakMs(dp.readText())) + speakMs(dp.thanks());
        synchronized (statsLock) {
            turns++;
            remember(dp.thanks());
        }
        template.convertAndSend("/topic/donation-speak", new DonationSpeak(turnNo, dp.kind().name(), dp.items(),
                dp.readText(), dp.thanks(), ms, donations.stats(), donations.snapshot(),
                director.status(System.currentTimeMillis())));
        scheduleTurn(ms + pauseAfter("donation"));
    }

    /** 질문·답 정리·묶음 인정처럼 한 사람에게 하는 말이 아닌 차례 (템플릿, LLM 안 씀) */
    private void speakSpecial(int turnNo, String kind, LiveRoom.GroupMoment g, String ruleLabel, int bots) {
        long ms = speakMs(g.line());
        cheerFor(kind);
        synchronized (statsLock) {
            turns++;
            remember(g.line());
        }
        template.convertAndSend("/topic/speak", new SpeakView(turnNo, kind, null, g, List.of(),
                ms, 0, room.stats(), ruleLabel, bots, llmView(), voiceView(), room.returnStats(),
                director.status(System.currentTimeMillis())));
        scheduleTurn(ms + pauseAfter(kind));
    }

    /** statsLock 안에서 부른다 */
    private void remember(String line) {
        recentLines.addLast(line);
        while (recentLines.size() > 5) recentLines.pollFirst();
    }

    private void recordTalk(HaikuLineWriter.TalkResult res) {
        synchronized (statsLock) {
            if ("llm".equals(res.source())) llmLines++;
            else templateLines++;
            if (!res.called()) return;
            llmTimes.add(res.llmMs());
            if (res.rejected() != null) rejected.merge("이어 말하기: " + res.rejected(), 1L, Long::sum);
            inTokens += res.inputTokens();
            outTokens += res.outputTokens();
        }
    }

    private int answerTurn;

    /**
     * 질문의 답을 모으는 중이면, 주제 분류가 모르는 답("집에서 쉴래요")도 그 질문의 답 칸으로 반응한다.
     * 처음엔 이런 답에 "말 들었어요! 고마워요~" 같은 아무 말이 나갔다 (사용자 피드백 2026-11-17).
     * 음식·게임·피곤처럼 이미 맞는 반응이 있는 주제는 그대로 둔다
     */
    private List<LiveRoom.Reaction> answerLines(List<LiveRoom.Reaction> rs, Director.Question q) {
        if (q == null) return rs;
        List<LiveRoom.Reaction> out = new ArrayList<>();
        for (LiveRoom.Reaction x : rs) {
            String bucket = q.bucketOf(x.chatText());
            boolean generic = x.topic() == com.livecharacter.engine.LineWriter.Topic.OTHER
                    || x.topic() == com.livecharacter.engine.LineWriter.Topic.GREETING
                    || x.topic() == com.livecharacter.engine.LineWriter.Topic.QUESTION;
            if (bucket == null || !generic || x.recall() != null) {
                out.add(x);
                continue;
            }
            String label = bucket.equals("좋았던 분") ? "좋은 하루" : bucket.equals("피곤한 분") ? "피곤한 하루" : bucket;
            String quote = x.chatText().length() <= 16 ? "\"" + x.chatText() + "\" " : "";
            String line = switch (answerTurn++ % 3) {
                case 0 -> quote + label + " 한 표 추가요!";
                case 1 -> x.nickname() + "님은 " + label + "! 좋다~";
                default -> quote + "오, " + label + "! 좋아요~";
            };
            out.add(x.withLine(line, com.livecharacter.engine.LineWriter.Emotion.EXCITED, "template"));
        }
        return out;
    }

    private void record(HaikuLineWriter.Result res) {
        synchronized (statsLock) {
            for (LiveRoom.Reaction x : res.reactions()) {
                if ("llm".equals(x.source())) llmLines++;
                else templateLines++;
            }
            if (!res.called()) return;
            llmTimes.add(res.llmMs());
            for (String why : res.rejected()) rejected.merge(why, 1L, Long::sum);
            inTokens += res.inputTokens();
            outTokens += res.outputTokens();
        }
    }

    private LlmView llmView() {
        synchronized (statsLock) {
            List<Long> sorted = new ArrayList<>(llmTimes);
            Collections.sort(sorted);
            return new LlmView(llm.enabled(), Math.min(llm.callsUsed(), llm.maxCalls()), llm.maxCalls(),
                    pct(sorted, 0.5), pct(sorted, 0.95), llmLines, templateLines,
                    new LinkedHashMap<>(rejected), inTokens, outTokens);
        }
    }

    private VoiceView voiceView() {
        synchronized (statsLock) {
            List<Long> sorted = new ArrayList<>(voiceStarts);
            Collections.sort(sorted);
            double min = Math.max(1, System.currentTimeMillis() - voiceSince) / 60000.0;
            return new VoiceView(turns, turns / min, pct(sorted, 0.5), pct(sorted, 0.95), groupTurns);
        }
    }

    private static long pct(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    public void humanChat(String viewerId, String nickname, String text) {
        if (viewerId == null || !VIEWER_ID.matcher(viewerId).matches() || text == null || text.isBlank()) return;
        String nick = nickname == null || nickname.isBlank() ? "나" : nickname.strip();
        if (nick.length() > 12) nick = nick.substring(0, 12);
        room.receive("me-" + viewerId, nick, text, 0, System.currentTimeMillis(), true);
    }

    /**
     * 지금 라이브를 끝내고 다음 라이브를 시작한다. 숫자는 새로 세고 팬 기억은 남는다.
     * 봇은 같은 봇들이 다시 온다 (데모라서 복귀 여부는 시뮬레이션 테스트 ReturnRateTest에서 따로 본다)
     */
    /**
     * "라이브 끝내고 다음 라이브": 바로 바꾸지 않고, 다음 차례에 마무리 인사를 한 뒤 바꾼다 (실제 방송처럼).
     */
    public synchronized void nextLive() {
        endRequested = true;
    }

    private volatile boolean endRequested;
    private volatile boolean switchPending;

    /** 마무리 인사를 마친 뒤 실제로 다음 라이브로 바꾼다 */
    private synchronized void switchLive() {
        lastLive = room.startNextLive();
        donations = new DonationQueue();
        director = new Director();
        director.startLive(System.currentTimeMillis(), true); // 지난 라이브 요약 오프닝이 인사를 대신한다
        audience.answering(null);
        pendingOpening = lastLive.openingLine();
        synchronized (statsLock) {
            voiceStarts.clear();
            turns = 0;
            groupTurns = 0;
            voiceSince = System.currentTimeMillis();
        }
        template.convertAndSend("/topic/state", state());
    }

    /** 규칙이나 봇 수를 바꾸면 숫자를 처음부터 다시 센다 (LLM 호출 수·비용은 서버를 켠 동안 누적) */
    public synchronized void control(String ruleId, Integer bots) {
        if (ruleId != null) rule = PickRule.byId(ruleId);
        if (bots != null && ALLOWED_BOTS.contains(bots)) audience = new BotAudience(bots, seed, botGiftScale);
        donations = new DonationQueue();
        room.reset(rule);
        director = new Director();
        director.startLive(System.currentTimeMillis(), false);
        synchronized (statsLock) {
            voiceStarts.clear();
            turns = 0;
            groupTurns = 0;
            voiceSince = System.currentTimeMillis();
        }
        template.convertAndSend("/topic/state", state());
    }

    public StateView state() {
        List<RuleView> rules = PickRule.all().stream().map(r -> new RuleView(r.id(), r.label())).toList();
        return new StateView(rule.id(), rule.label(), audience.bots().size(), screenK, groupEvery, rules,
                room.stats(), llmView(), voiceView(), room.returnStats(), lastLive);
    }
}
