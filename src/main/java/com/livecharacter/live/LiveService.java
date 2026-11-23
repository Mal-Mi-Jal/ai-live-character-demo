package com.livecharacter.live;

import com.livecharacter.engine.Chat;
import com.livecharacter.engine.Director;
import com.livecharacter.engine.DonationQueue;
import com.livecharacter.engine.LineWriter;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 순수 Java 엔진(LiveRoom, BotAudience, Director)을 실제 시계에 물려 돌리고, 결과를 STOMP로 내보낸다.
 *
 * 음성 동기화 개선:
 *  - 대사를 브로드캐스트하기 전에 백엔드 TtsService.prepare()로 mp3를 미리 합성·캐시하고,
 *    실제 오디오 길이(durationMs)를 speakMs로 내려줘서 음성이 중간에 잘리지 않고 진행 바와 정확히 맞물린다.
 *  - 방송 준비중(PRE_LIVE) → 시작 인사(OPENING) → 본 방송(LIVE) → 마무리(CLOSING) 단계로 봇 채팅 분위기를 전환한다.
 */
@Service
public class LiveService {

    public record ChatView(long seq, String viewerId, String nickname, String text, int gift, boolean human, String kind) {
    }

    public record StateView(String rule, String ruleLabel, int bots, int screenK, int groupEvery,
                            List<RuleView> rules, LiveRoom.Stats stats, LlmView llm, VoiceView voice,
                            LiveRoom.ReturnStats returns, LiveRoom.LiveSummary lastLive) {
    }

    public record LlmView(boolean enabled, int callsUsed, int maxCalls, long llmP50Ms, long llmP95Ms,
                          long llmLines, long templateLines, Map<String, Long> rejected,
                          long inputTokens, long outputTokens) {
    }

    public record VoiceView(int turns, double turnsPerMin, long startP50Ms, long startP95Ms, int groupTurns) {
    }

    public record RuleView(String id, String label) {
    }

    /** 후원 읽기 차례. readMs = 시스템 음성 실제 길이, speakMs = 전체 차례 길이(읽기 + 솔라 감사) */
    public record DonationSpeak(int turn, String kind, List<DonationQueue.Donation> items, String readText,
                                String thanks, long readMs, long speakMs, DonationQueue.Stats stats,
                                List<DonationQueue.Donation> queue, Director.Status topic) {
    }

    public record DonationBoard(List<DonationQueue.Donation> queue, DonationQueue.Stats stats) {
    }

    public record SpeakView(int turn, String kind, LiveRoom.Reaction reaction, LiveRoom.GroupMoment group,
                            List<LiveRoom.Ack> screenAcks, long speakMs, long llmMs, LiveRoom.Stats stats,
                            String ruleLabel, int bots, LlmView llm, VoiceView voice, LiveRoom.ReturnStats returns,
                            Director.Status topic) {
    }

    private static final Set<Integer> ALLOWED_BOTS = Set.of(0, 100, 300, 1000);
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final long IDLE_MS = 1000;
    private static final long GAP_MS = 450;
    private static final long DONATION_GAP_MS = 350;

    static long pauseAfter(String kind) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        return switch (kind) {
            case "ask", "balance_start" -> r.nextLong(2600, 4000);
            case "answer" -> r.nextLong(1100, 1900);
            case "episode" -> r.nextLong(2200, 3200);
            case "talk", "story", "answers", "opening", "balance_end", "episode_end" -> r.nextLong(2000, 3400);
            default -> r.nextLong(1500, 2800);
        };
    }

    private final SimpMessagingTemplate template;
    private final HaikuLineWriter llm;
    private final TtsService tts;
    private final long llmTimeoutMs;
    private final double charsPerSec;
    private final int screenK;
    private final int groupEvery;
    private final int chatFlushMs;
    private final int botStepMs;
    private final long seed;

    private final Object statsLock = new Object();
    private final List<Long> llmTimes = new ArrayList<>();
    private final Map<String, Long> rejected = new LinkedHashMap<>();
    private long llmLines, templateLines, inTokens, outTokens;
    private final List<Long> voiceStarts = new ArrayList<>();
    private final java.util.ArrayDeque<String> recentLines = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> recentTalks = new java.util.ArrayDeque<>();
    private int turns, groupTurns;
    private long voiceSince = System.currentTimeMillis();

    private final LiveRoom room;
    private volatile BotAudience audience;
    private volatile PickRule rule = PickRule.FAIR;
    private volatile LiveRoom.LiveSummary lastLive;
    private volatile Director director = new Director();
    private volatile DonationQueue donations = new DonationQueue();
    private final List<ChatView> donationChats = new ArrayList<>();
    private boolean donationsChanged;
    private long donationSeq;
    private volatile int turnsSinceDonation = 99;
    private static final int DONATION_EVERY = 3;
    private final double botGiftScale;
    private volatile String pendingOpening;
    private ScheduledExecutorService scheduler;
    private ExecutorService ttsPool;

    public LiveService(SimpMessagingTemplate template, HaikuLineWriter llm, TtsService tts,
                       @Value("${live.llm-timeout-ms}") long llmTimeoutMs,
                       @Value("${live.voice-chars-per-sec}") double charsPerSec,
                       @Value("${live.screen-acks}") int screenK,
                       @Value("${live.group-every}") int groupEvery,
                       @Value("${live.voice-max-age-ms}") long voiceMaxAgeMs,
                       @Value("${live.bot-gift-scale}") double botGiftScale,
                       @Value("${live.bots}") int bots,
                       @Value("${live.chat-flush-ms}") int chatFlushMs,
                       @Value("${live.bot-step-ms}") int botStepMs,
                       @Value("${live.seed}") long seed,
                       @Value("${live.regular-every:0}") int regularEvery) {
        this.template = template;
        this.llm = llm;
        this.tts = tts;
        this.llmTimeoutMs = llmTimeoutMs;
        this.charsPerSec = charsPerSec;
        this.screenK = screenK;
        this.groupEvery = groupEvery;
        this.chatFlushMs = chatFlushMs;
        this.botStepMs = botStepMs;
        this.seed = seed;
        this.room = new LiveRoom(rule, 1, screenK, voiceMaxAgeMs, seed);
        room.setEchoStyle(true);
        room.setRegularEvery(regularEvery);
        director.setShowHostMode(true);
        this.botGiftScale = botGiftScale;
        this.audience = new BotAudience(bots, seed, botGiftScale, true);
        this.audience.setPhase(BotAudience.Phase.PRE_LIVE);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "live-loop"));
        ttsPool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "tts-prep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(guard(this::botStep), botStepMs, botStepMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(guard(this::flushChat), chatFlushMs, chatFlushMs, TimeUnit.MILLISECONDS);
        director.startLive(System.currentTimeMillis() + 3500, false);
        scheduleTurn(3500);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (ttsPool != null) ttsPool.shutdownNow();
    }

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
        if (scheduler == null || scheduler.isShutdown()) return;
        scheduler.schedule(() -> {
            try {
                turn();
            } catch (RuntimeException e) {
                System.err.println("[live-loop] " + e);
                scheduleTurn(IDLE_MS);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void botStep() {
        long now = System.currentTimeMillis();
        // 오프닝이 끝나면 봇 단계를 자연스럽게 LIVE로 전환
        if (audience.phase() == BotAudience.Phase.OPENING && !director.inOpening(now)) {
            audience.setPhase(BotAudience.Phase.LIVE);
        }
        for (BotAudience.Sent s : audience.step(botStepMs)) {
            if (s.gift() > 0) donate(s.bot().id(), s.bot().nickname(), s.gift(), s.text(), false, now);
            else room.receive(s.bot().id(), s.bot().nickname(), s.text(), 0, now, false);
        }
    }

    private void donate(String viewerId, String nickname, int amount, String text, boolean human, long now) {
        DonationQueue.Donation d = donations.offer(viewerId, nickname, amount, text, human, now);
        String kind = viewerKind(viewerId, human);
        synchronized (donationChats) {
            donationChats.add(new ChatView(-(++donationSeq), viewerId, d.nickname(), d.message() == null ? "" : d.message(), amount, human, kind));
            donationsChanged = true;
        }
    }

    private String viewerKind(String viewerId, boolean human) {
        if (human) return "HUMAN";
        BotAudience.Kind k = audience.kindOf(viewerId);
        return k == null ? "NORMAL" : k.name();
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
        if (director.activeBalance() != null) {
            template.convertAndSend("/topic/topic", director.status(System.currentTimeMillis(), room.recentChats()));
        }
        List<Chat> chats = room.takeUnflushed();
        if (chats.isEmpty() && extra.isEmpty()) return;
        List<ChatView> view = chats.stream()
                .skip(Math.max(0, chats.size() - 60))
                .map(c -> new ChatView(c.seq(), c.viewerId(), c.nickname(), c.text(), c.gift(), c.human(), viewerKind(c.viewerId(), c.human())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        view.addAll(extra);
        template.convertAndSend("/topic/chat", view);
    }

    long estimateSpeakMs(String line) {
        return Math.round(line.length() / charsPerSec * 1000) + GAP_MS;
    }

    /** TTS를 미리 합성·캐시하고 실제 mp3 길이에 여유 버퍼(450ms)를 더해 반환한다 */
    private long prepareAudioMs(String line, String role, String emotion) {
        if (line == null || line.isBlank()) return 0;
        TtsService.Prepared p = tts.prepare(line, role, emotion);
        return p != null ? p.durationMs() + GAP_MS : estimateSpeakMs(line);
    }

    private static String emotionForSpecial(String kind) {
        return switch (kind) {
            case "ask" -> "THINKING";
            case "answers", "group", "balance_start", "balance_mid", "episode" -> "EXCITED";
            case "opening", "closing" -> "TOUCHED";
            case "story" -> "CALM";
            default -> "HAPPY";
        };
    }

    private void turn() {
        long now = System.currentTimeMillis();
        String ruleLabel = rule.label();
        int bots = audience.bots().size();
        if (switchPending) {
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
            audience.setPhase(BotAudience.Phase.CLOSING);
            int fans = room.stats().chatters();
            String closing = "오늘 방송은 여기까지! 와 준 " + fans + "명 다 고마워요. 다음 라이브에서 또 만나요!";
            speakSpecial(turnNo, "closing", new LiveRoom.GroupMoment("마무리", fans, List.of(), closing), ruleLabel, bots);
            return;
        }
        String opening = pendingOpening;
        if (opening != null) {
            pendingOpening = null;
            audience.setPhase(BotAudience.Phase.OPENING);
            LiveRoom.LiveSummary s = lastLive;
            LiveRoom.GroupMoment g = new LiveRoom.GroupMoment(s.topLabel() == null ? "지난 라이브" : s.topLabel(),
                    s.topCount(), List.of(), opening);
            speakSpecial(turnNo, "opening", g, ruleLabel, bots);
            return;
        }
        Director d = director;
        DonationQueue.Plan dp = donations.next(now, d.inSegment(), turnsSinceDonation >= DONATION_EVERY - 1);
        if (dp.kind() != DonationQueue.Kind.NONE) {
            turnsSinceDonation = 0;
            speakDonation(turnNo, dp);
            return;
        }
        turnsSinceDonation++;
        boolean groupTurn = groupEvery > 0 && turnNo % groupEvery == 0;
        Director.Plan plan = d.next(now, room.hotTopics(now), groupTurn);

        if (plan.kind() == Director.Kind.BALANCE_START) {
            audience.setPhase(BotAudience.Phase.LIVE);
            Director.StartedBalance sb = d.startBalance(now, room.recentChats());
            audience.answering(sb.game().allBotChats());
            List<String> mentions = sb.triggerNick() == null ? List.of() : List.of(sb.triggerNick());
            speakSpecial(turnNo, "balance_start", new LiveRoom.GroupMoment(sb.game().title(), 0, mentions, sb.introLine()), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.BALANCE_MID) {
            String midLine = d.balanceMidLine(room.recentChats());
            Director.BalanceGame bg = d.activeBalance();
            speakSpecial(turnNo, "balance_mid", new LiveRoom.GroupMoment(bg == null ? "밸런스 게임" : bg.title(), 0, List.of(), midLine), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.BALANCE_END) {
            Director.BalanceResult br = d.finishBalance(room.recentChats(), now);
            audience.answering(null);
            room.ackAnswers(br.voters());
            String label = br.game().optionA() + " " + br.countA() + " vs " + br.game().optionB() + " " + br.countB();
            speakSpecial(turnNo, "balance_end", new LiveRoom.GroupMoment(label, br.voters().size(), List.of(), br.line()), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.EPISODE_START) {
            audience.setPhase(BotAudience.Phase.LIVE);
            Director.Episode ep = d.startEpisode(now);
            speakSpecial(turnNo, "episode", new LiveRoom.GroupMoment(ep.title(), 0, List.of(), ep.part1()), ruleLabel, bots);
            audience.cheer(ep.cheers1(), 0.78, 18_000);
            return;
        }
        if (plan.kind() == Director.Kind.EPISODE_END) {
            Director.Episode ep = d.finishEpisode();
            if (ep != null) {
                speakSpecial(turnNo, "episode_end", new LiveRoom.GroupMoment(ep.title(), 0, List.of(), ep.part2()), ruleLabel, bots);
                audience.cheer(ep.cheers2(), 0.6, 10_000);
                return;
            }
        }
        if (plan.kind() == Director.Kind.ASK) {
            audience.setPhase(BotAudience.Phase.LIVE);
            String line = d.startAsk(plan.question(), plan.preface(), now);
            audience.answeringQuestion(plan.question().id(), plan.question().botAnswers());
            speakSpecial(turnNo, "ask", new LiveRoom.GroupMoment(plan.question().label(), 0, List.of(), line), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.ANSWERS) {
            Director.Answers a = d.finishAsk(room.recentChats(), room.voiceCalled(), now);
            audience.answering(null);
            room.ackAnswers(a.answerers());
            String label = a.counts().entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                    .reduce((x, y) -> x + " · " + y).orElse("답 없음");
            List<String> mentions = a.highlightNick() == null ? List.of() : List.of(a.highlightNick());
            speakSpecial(turnNo, "answers", new LiveRoom.GroupMoment(label, a.total(), mentions, a.line()), ruleLabel, bots);
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
            llm.talk(talk, recent, others, talks)
                    .completeOnTimeout(new HaikuLineWriter.TalkResult(talk.templateLine(), LineWriter.Emotion.HAPPY,
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
            audience.setPhase(BotAudience.Phase.OPENING);
            speakSpecial(turnNo, "hello", new LiveRoom.GroupMoment("인사", 0, List.of(), plan.preface()), ruleLabel, bots);
            return;
        }
        if (plan.kind() == Director.Kind.STORY) {
            Director.Story story = d.nextStory();
            speakSpecial(turnNo, "story", new LiveRoom.GroupMoment("솔라 이야기", 0, List.of(), story.line()), ruleLabel, bots);
            audience.cheer(story.cheers(), 0.55, 10_000);
            return;
        }
        if (plan.kind() == Director.Kind.GROUP) {
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
        // 진행 중인 코너(에피소드 티키타카 / 밸런스 게임 유도 / 질문 답)에 맞춘 맥락형 대사로 교체
        List<LiveRoom.Reaction> reactions = contextualLines(r.reactions(), d);
        llm.rewrite(reactions)
                .completeOnTimeout(new HaikuLineWriter.Result(reactions, true, llmTimeoutMs,
                        List.of("시간 초과"), 0, 0), llmTimeoutMs, TimeUnit.MILLISECONDS)
                .thenAcceptAsync(res -> {
                    LiveRoom.Reaction x = res.reactions().get(0);
                    long llmMs = res.called() ? res.llmMs() : 0;
                    long ms = prepareAudioMs(x.line(), "solar", x.emotion().name());
                    synchronized (statsLock) {
                        turns++;
                        remember(x.line());
                        voiceStarts.add(x.waitMs() + (System.currentTimeMillis() - now));
                    }
                    record(res);
                    long pause = pauseAfter(d.active() != null || d.activeBalance() != null || d.activeEpisode() != null ? "answer" : "fan");
                    template.convertAndSend("/topic/speak", new SpeakView(turnNo, "fan", x, null, r.screenAcks(),
                            ms, llmMs, r.stats(), ruleLabel, bots, llmView(), voiceView(), room.returnStats(),
                            d.status(System.currentTimeMillis(), room.recentChats())));
                    scheduleTurn(ms + pause);
                }, ttsPool);
    }

    private static final List<String> CHEER_HELLO = List.of("안녕 솔라!", "하이하이~", "솔라 왔다!", "오늘도 반가워요", "ㅎㅇㅎㅇ", "별빛 라디오 출석!");
    private static final List<String> CHEER_REACT = List.of("ㅋㅋㅋㅋ", "귀여워ㅠㅠ", "솔라 최고", "오 진짜?", "ㅋㅋㅋ 솔라답다", "나도 그거!");
    private static final List<String> CHEER_ANSWERS = List.of("오 1등이 이거네ㅋㅋ", "역시ㅋㅋㅋ", "나도 그거였는데!", "솔라 집계 귀엽다", "오 박빙이네ㅋㅋ");
    private static final List<String> CHEER_BIG = List.of("오 만원 ㄷㄷ", "큰손님 등장!", "와 통 크시다", "꼬리별 날아가겠다ㅋㅋ");
    private static final List<String> CHEER_BYE = List.of("수고했어요!", "잘 자 솔라~", "다음에 또 올게요", "오늘 재밌었어요!", "빠이빠이 반짝!");

    private void cheerFor(String kind) {
        switch (kind) {
            case "hello", "opening" -> audience.cheer(CHEER_HELLO, 0.65, 20_000);
            case "answers", "balance_end" -> audience.cheer(CHEER_ANSWERS, 0.45, 8_000);
            case "talk" -> audience.cheer(CHEER_REACT, 0.35, 8_000);
            case "closing" -> audience.cheer(CHEER_BYE, 0.7, 15_000);
            default -> {
            }
        }
    }

    private void speakDonation(int turnNo, DonationQueue.Plan dp) {
        if (dp.items().stream().anyMatch(x -> x.amount() >= DonationQueue.BIG_AT)) audience.cheer(CHEER_BIG, 0.4, 7_000);
        CompletableFuture<Long> readFut = dp.readText() == null
                ? CompletableFuture.completedFuture(0L)
                : CompletableFuture.supplyAsync(() -> prepareAudioMs(dp.readText(), "system", "HAPPY"), ttsPool);
        CompletableFuture<Long> thanksFut = CompletableFuture.supplyAsync(
                () -> prepareAudioMs(dp.thanks(), "solar", "TOUCHED"), ttsPool);

        readFut.thenCombine(thanksFut, (readMs, thanksMs) -> {
            long totalMs = (readMs > 0 ? readMs + DONATION_GAP_MS : 0) + thanksMs + 250;
            synchronized (statsLock) {
                turns++;
                remember(dp.thanks());
            }
            template.convertAndSend("/topic/donation-speak", new DonationSpeak(turnNo, dp.kind().name(), dp.items(),
                    dp.readText(), dp.thanks(), readMs, totalMs, donations.stats(), donations.snapshot(),
                    director.status(System.currentTimeMillis(), room.recentChats())));
            scheduleTurn(totalMs + pauseAfter("donation"));
            return null;
        });
    }

    private void speakSpecial(int turnNo, String kind, LiveRoom.GroupMoment g, String ruleLabel, int bots) {
        String emo = emotionForSpecial(kind);
        CompletableFuture.supplyAsync(() -> prepareAudioMs(g.line(), "solar", emo), ttsPool)
                .thenAccept(ms -> {
                    cheerFor(kind);
                    synchronized (statsLock) {
                        turns++;
                        remember(g.line());
                    }
                    template.convertAndSend("/topic/speak", new SpeakView(turnNo, kind, null, g, List.of(),
                            ms, 0, room.stats(), ruleLabel, bots, llmView(), voiceView(), room.returnStats(),
                            director.status(System.currentTimeMillis(), room.recentChats())));
                    scheduleTurn(ms + pauseAfter(kind));
                });
    }

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
     * 진행 중인 코너에 맞춰 솔라가 맥락 있는 티키타카 대사를 만들도록 보정한다.
     * 1) 밸런스 게임 중 → A/B 진영 의견에 솔라 본인 취향 + 유도 멘트로 답장
     * 2) 솔라 에피소드 중 → 시청자의 에피소드 반응에 딱 맞는 티키타카 답장
     * 3) 질문 코너 중 → 답 칸에 맞는 자연스러운 반응
     */
    private List<LiveRoom.Reaction> contextualLines(List<LiveRoom.Reaction> rs, Director d) {
        Director.BalanceGame bg = d.activeBalance();
        if (bg != null) {
            List<LiveRoom.Reaction> out = new ArrayList<>();
            for (LiveRoom.Reaction x : rs) {
                String reply = d.balanceChatReply(new Chat(0, x.viewerId(), x.nickname(), x.chatText(), x.gift(), 0, x.human()));
                out.add(reply != null ? x.withLine(reply, LineWriter.Emotion.EXCITED, "template") : x);
            }
            return out;
        }
        Director.Episode ep = d.activeEpisode();
        if (ep != null) {
            List<LiveRoom.Reaction> out = new ArrayList<>();
            for (LiveRoom.Reaction x : rs) {
                String reply = ep.replyFor(x.chatText(), x.nickname());
                // 이야기와 상관없는 채팅이면 평소 대사 그대로 (예전엔 이야기 대사를 무조건 붙였음)
                out.add(reply != null ? x.withLine(reply, LineWriter.Emotion.HAPPY, "template") : x);
            }
            return out;
        }
        return answerLines(rs, d.active());
    }

    private List<LiveRoom.Reaction> answerLines(List<LiveRoom.Reaction> rs, Director.Question q) {
        if (q == null) return rs;
        List<LiveRoom.Reaction> out = new ArrayList<>();
        for (LiveRoom.Reaction x : rs) {
            String bucket = q.bucketOf(x.chatText());
            boolean generic = x.topic() == LineWriter.Topic.OTHER
                    || x.topic() == LineWriter.Topic.GREETING
                    || x.topic() == LineWriter.Topic.QUESTION;
            if (bucket == null || !generic || x.recall() != null) {
                out.add(x);
                continue;
            }
            String label = bucket.equals("좋았던 분") ? "좋은 하루" : bucket.equals("피곤한 분") ? "피곤한 하루" : bucket;
            String quote = x.chatText().length() <= 16 ? "\"" + x.chatText() + "\" " : "";
            String line = switch (answerTurn++ % 6) {
                case 0 -> quote + label + " 한 표 추가요!";
                case 1 -> x.nickname() + "님은 " + label + "! 좋다~";
                case 2 -> quote + "오, " + label + "! 솔라도 메모했어요~";
                case 3 -> x.nickname() + "님 " + label + " 픽! 반짝!";
                case 4 -> quote + label + " 파가 여기 또 있네요!";
                default -> x.nickname() + "님도 " + label + "이구나! 잘 들었어요~";
            };
            out.add(x.withLine(line, LineWriter.Emotion.EXCITED, "template"));
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

    public synchronized void nextLive() {
        endRequested = true;
    }

    private volatile boolean endRequested;
    private volatile boolean switchPending;

    public void triggerCorner(String corner) {
        director.requestCorner(corner);
    }

    private synchronized void switchLive() {
        lastLive = room.startNextLive();
        donations = new DonationQueue();
        director = new Director();
        director.setShowHostMode(true);
        director.startLive(System.currentTimeMillis(), true);
        audience.answering(null);
        audience.setPhase(BotAudience.Phase.PRE_LIVE);
        pendingOpening = lastLive.openingLine();
        synchronized (statsLock) {
            voiceStarts.clear();
            turns = 0;
            groupTurns = 0;
            voiceSince = System.currentTimeMillis();
        }
        template.convertAndSend("/topic/state", state());
    }

    public synchronized void control(String ruleId, Integer bots) {
        if (ruleId != null) rule = PickRule.byId(ruleId);
        if (bots != null && ALLOWED_BOTS.contains(bots)) audience = new BotAudience(bots, seed, botGiftScale, true);
        audience.setPhase(BotAudience.Phase.PRE_LIVE);
        donations = new DonationQueue();
        room.reset(rule);
        director = new Director();
        director.setShowHostMode(true);
        director.startLive(System.currentTimeMillis() + 2500, false);
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
