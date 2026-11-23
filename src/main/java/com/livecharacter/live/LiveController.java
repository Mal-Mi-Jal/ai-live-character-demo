package com.livecharacter.live;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

@Controller
public class LiveController {

    public record ChatIn(String viewerId, String nickname, String text) {
    }

    public record DonateIn(String viewerId, String nickname, Integer amount, String text) {
    }

    public record ControlIn(String rule, Integer bots, Boolean nextLive, String trigger) {
    }

    private final LiveService live;

    public LiveController(LiveService live) {
        this.live = live;
    }

    @MessageMapping("/chat")
    public void chat(ChatIn in) {
        live.humanChat(in.viewerId(), in.nickname(), in.text());
    }

    @MessageMapping("/donate")
    public void donate(DonateIn in) {
        live.humanDonate(in.viewerId(), in.nickname(), in.amount(), in.text());
    }

    @MessageMapping("/control")
    public void control(ControlIn in) {
        if (Boolean.TRUE.equals(in.nextLive())) live.nextLive();
        else if (in.trigger() != null && !in.trigger().isBlank()) live.triggerCorner(in.trigger());
        else live.control(in.rule(), in.bots());
    }

    /** 처음 접속한 화면이 다음 틱을 기다리지 않고 바로 현재 상태를 받게 한다 */
    @SubscribeMapping("/state")
    public LiveService.StateView state() {
        return live.state();
    }
}
