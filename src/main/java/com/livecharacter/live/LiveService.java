package com.livecharacter.live;

import com.livecharacter.engine.Chat;
import com.livecharacter.engine.LiveRoom;
import com.livecharacter.engine.PickRule;
import com.livecharacter.sim.BotAudience;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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
                            List<RuleView> rules, LiveRoom.Stats stats) {
    }

    public record RuleView(String id, String label) {
    }

    public record TickView(int tickNo, List<LiveRoom.Reaction> reactions, LiveRoom.Stats stats,
                           String ruleLabel, int bots, double tickProcessMs) {
    }

    private static final Set<Integer> ALLOWED_BOTS = Set.of(0, 100, 300, 1000);
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final SimpMessagingTemplate template;
    private final int tickMs;
    private final int k;
    private final int chatFlushMs;
    private final int botStepMs;
    private final long seed;

    private final LiveRoom room;
    private volatile BotAudience audience;
    private volatile PickRule rule = PickRule.RANDOM;
    private ScheduledExecutorService scheduler;

    public LiveService(SimpMessagingTemplate template,
                       @Value("${live.bots}") int bots,
                       @Value("${live.tick-ms}") int tickMs,
                       @Value("${live.react-k}") int k,
                       @Value("${live.chat-flush-ms}") int chatFlushMs,
                       @Value("${live.bot-step-ms}") int botStepMs,
                       @Value("${live.seed}") long seed) {
        this.template = template;
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
        template.convertAndSend("/topic/tick",
                new TickView(r.tickNo(), r.reactions(), r.stats(), rule.label(), audience.bots().size(), processMs));
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
        return new StateView(rule.id(), rule.label(), audience.bots().size(), tickMs, k, rules, room.stats());
    }
}
