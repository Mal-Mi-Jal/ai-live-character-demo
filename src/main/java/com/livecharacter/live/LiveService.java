package com.livecharacter.live;

import com.livecharacter.engine.Chat;
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
                            List<RuleView> rules, LiveRoom.Stats stats, LlmView llm, VoiceView voice) {
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

    /**
     * 한 차례 말하기. kind = "fan"(한 명 이름 부르기) 또는 "group"(묶음 인정).
     * speakMs = 이 대사를 읽는 데 걸리는 시간 (서버가 다음 차례를 이만큼 뒤에 연다)
     */
    public record SpeakView(int turn, String kind, LiveRoom.Reaction reaction, LiveRoom.GroupMoment group,
                            List<LiveRoom.Ack> screenAcks, long speakMs, long llmMs, LiveRoom.Stats stats,
                            String ruleLabel, int bots, LlmView llm, VoiceView voice) {
    }

    private static final Set<Integer> ALLOWED_BOTS = Set.of(0, 100, 300, 1000);
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    /** 말할 사람이 없을 때 다시 볼 간격 */
    private static final long IDLE_MS = 1000;
    /** 대사와 대사 사이 숨 고르기 */
    private static final long GAP_MS = 400;

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
    private int turns, groupTurns;
    private long voiceSince = System.currentTimeMillis();

    private final LiveRoom room;
    private volatile BotAudience audience;
    private volatile PickRule rule = PickRule.FAIR;
    private ScheduledExecutorService scheduler;

    public LiveService(SimpMessagingTemplate template, HaikuLineWriter llm,
                       @Value("${live.llm-timeout-ms}") long llmTimeoutMs,
                       @Value("${live.voice-chars-per-sec}") double charsPerSec,
                       @Value("${live.screen-acks}") int screenK,
                       @Value("${live.group-every}") int groupEvery,
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
        this.room = new LiveRoom(rule, 1, screenK, seed);
        this.audience = new BotAudience(bots, seed);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "live-loop"));
        scheduler.scheduleAtFixedRate(guard(this::botStep), botStepMs, botStepMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(guard(this::flushChat), chatFlushMs, chatFlushMs, TimeUnit.MILLISECONDS);
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
            room.receive(s.bot().id(), s.bot().nickname(), s.text(), s.gift(), now, false);
        }
    }

    private void flushChat() {
        List<Chat> chats = room.takeUnflushed();
        if (chats.isEmpty()) return;
        // 화면에는 최근 것만 보이면 된다. 1000명일 때 0.5초 묶음이 수십 개라도 한 메시지로 간다
        List<ChatView> view = chats.stream()
                .skip(Math.max(0, chats.size() - 60))
                .map(c -> new ChatView(c.seq(), c.nickname(), c.text(), c.gift(), c.human()))
                .toList();
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
        int turnNo;
        synchronized (statsLock) {
            turnNo = turns + 1;
        }
        if (groupEvery > 0 && turnNo % groupEvery == 0) {
            LiveRoom.GroupMoment g = room.groupMoment(now);
            if (g != null) {
                long ms = speakMs(g.line());
                synchronized (statsLock) {
                    turns++;
                    groupTurns++;
                }
                template.convertAndSend("/topic/speak", new SpeakView(turnNo, "group", null, g, List.of(),
                        ms, 0, room.stats(), ruleLabel, bots, llmView(), voiceView()));
                scheduleTurn(ms);
                return;
            }
        }
        LiveRoom.TickResult r = room.tick(now);
        if (r.reactions().isEmpty()) {
            scheduleTurn(IDLE_MS);
            return;
        }
        // LLM은 방 잠금 밖, 스케줄러 스레드 밖에서 부른다. 늦으면 템플릿 대사가 나간다
        llm.rewrite(r.reactions())
                .completeOnTimeout(new HaikuLineWriter.Result(r.reactions(), true, llmTimeoutMs,
                        List.of("시간 초과"), 0, 0), llmTimeoutMs, TimeUnit.MILLISECONDS)
                .thenAccept(res -> {
                    LiveRoom.Reaction x = res.reactions().get(0);
                    long llmMs = res.called() ? res.llmMs() : 0;
                    long ms = speakMs(x.line());
                    synchronized (statsLock) {
                        turns++;
                        // 채팅 → 목소리 시작 = 고르기까지 기다린 시간 + 대사 만드는 시간
                        voiceStarts.add(x.waitMs() + (System.currentTimeMillis() - now));
                    }
                    record(res);
                    template.convertAndSend("/topic/speak", new SpeakView(turnNo, "fan", x, null, r.screenAcks(),
                            ms, llmMs, r.stats(), ruleLabel, bots, llmView(), voiceView()));
                    scheduleTurn(ms);
                });
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

    /** 규칙이나 봇 수를 바꾸면 숫자를 처음부터 다시 센다 (LLM 호출 수·비용은 서버를 켠 동안 누적) */
    public synchronized void control(String ruleId, Integer bots) {
        if (ruleId != null) rule = PickRule.byId(ruleId);
        if (bots != null && ALLOWED_BOTS.contains(bots)) audience = new BotAudience(bots, seed);
        room.reset(rule);
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
                room.stats(), llmView(), voiceView());
    }
}
