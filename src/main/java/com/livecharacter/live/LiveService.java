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
 * 스레드 하나(scheduler)가 봇 스텝·채팅 전송·틱을 모두 돌린다 → 서로 겹치지 않는다.
 * 사람 채팅만 STOMP 스레드에서 들어오고, 그건 LiveRoom의 잠금이 지킨다.
 */
@Service
public class LiveService {

    public record ChatView(long seq, String nickname, String text, int gift, boolean human) {
    }

    public record StateView(String rule, String ruleLabel, int bots, int tickMs, int k,
                            List<RuleView> rules, LiveRoom.Stats stats, LlmView llm) {
    }

    /** LLM 대사 현황: 켜졌나, 몇 번 불렀나, 얼마나 걸렸나, 검사에 몇 줄 걸렸나 */
    public record LlmView(boolean enabled, int callsUsed, int maxCalls, long llmP50Ms, long llmP95Ms,
                          long llmLines, long templateLines, Map<String, Long> rejected,
                          long inputTokens, long outputTokens) {
    }

    public record RuleView(String id, String label) {
    }

    public record TickView(int tickNo, List<LiveRoom.Reaction> reactions, LiveRoom.Stats stats,
                           String ruleLabel, int bots, double tickProcessMs, long llmMs, LlmView llm) {
    }

    private static final Set<Integer> ALLOWED_BOTS = Set.of(0, 100, 300, 1000);
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final SimpMessagingTemplate template;
    private final HaikuLineWriter llm;
    private final long llmTimeoutMs;
    // LLM 숫자 (여러 스레드에서 갱신하므로 이 객체로 동기화)
    private final Object llmStatsLock = new Object();
    private final List<Long> llmTimes = new ArrayList<>();
    private final Map<String, Long> rejected = new LinkedHashMap<>();
    private long llmLines, templateLines, inTokens, outTokens;
    private final int tickMs;
    private final int k;
    private final int chatFlushMs;
    private final int botStepMs;
    private final long seed;

    private final LiveRoom room;
    private volatile BotAudience audience;
    private volatile PickRule rule = PickRule.RANDOM;
    private ScheduledExecutorService scheduler;

    public LiveService(SimpMessagingTemplate template, HaikuLineWriter llm,
                       @Value("${live.llm-timeout-ms}") long llmTimeoutMs,
                       @Value("${live.bots}") int bots,
                       @Value("${live.tick-ms}") int tickMs,
                       @Value("${live.react-k}") int k,
                       @Value("${live.chat-flush-ms}") int chatFlushMs,
                       @Value("${live.bot-step-ms}") int botStepMs,
                       @Value("${live.seed}") long seed) {
        this.template = template;
        this.llm = llm;
        this.llmTimeoutMs = llmTimeoutMs;
        this.tickMs = tickMs;
        this.k = k;
        this.chatFlushMs = chatFlushMs;
        this.botStepMs = botStepMs;
        this.seed = seed;
        this.room = new LiveRoom(rule, k, seed);
        this.audience = new BotAudience(bots, seed);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "live-loop"));
        scheduler.scheduleAtFixedRate(guard(this::botStep), botStepMs, botStepMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(guard(this::flushChat), chatFlushMs, chatFlushMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(guard(this::tick), tickMs, tickMs, TimeUnit.MILLISECONDS);
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

    private void tick() {
        long t0 = System.nanoTime();
        LiveRoom.TickResult r = room.tick(System.currentTimeMillis());
        double processMs = (System.nanoTime() - t0) / 1e6;
        String ruleLabel = rule.label();
        int bots = audience.bots().size();
        // LLM은 방 잠금 밖, 스케줄러 스레드 밖에서 부른다. 늦으면 템플릿 대사가 나간다
        llm.rewrite(r.reactions())
                .completeOnTimeout(new HaikuLineWriter.Result(r.reactions(), true, llmTimeoutMs,
                        List.of("시간 초과"), 0, 0), llmTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenAccept(res -> {
                    record(res);
                    template.convertAndSend("/topic/tick", new TickView(r.tickNo(), res.reactions(), r.stats(),
                            ruleLabel, bots, processMs, res.called() ? res.llmMs() : 0, llmView()));
                });
    }

    private void record(HaikuLineWriter.Result res) {
        synchronized (llmStatsLock) {
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
        synchronized (llmStatsLock) {
            List<Long> sorted = new ArrayList<>(llmTimes);
            Collections.sort(sorted);
            return new LlmView(llm.enabled(), Math.min(llm.callsUsed(), llm.maxCalls()), llm.maxCalls(),
                    pct(sorted, 0.5), pct(sorted, 0.95), llmLines, templateLines,
                    new LinkedHashMap<>(rejected), inTokens, outTokens);
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

    /** 규칙이나 봇 수를 바꾸면 숫자를 처음부터 다시 센다 */
    public synchronized void control(String ruleId, Integer bots) {
        if (ruleId != null) rule = PickRule.byId(ruleId);
        if (bots != null && ALLOWED_BOTS.contains(bots)) audience = new BotAudience(bots, seed);
        room.reset(rule);
        template.convertAndSend("/topic/state", state());
    }

    public StateView state() {
        List<RuleView> rules = PickRule.all().stream().map(r -> new RuleView(r.id(), r.label())).toList();
        return new StateView(rule.id(), rule.label(), audience.bots().size(), tickMs, k, rules, room.stats(), llmView());
    }
}
