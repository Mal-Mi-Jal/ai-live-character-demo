package com.livecharacter.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EchoLineTest {

    private static List<String> lines(LineWriter w, String chat, String recall, int n) {
        LineWriter.Detected d = LineWriter.detect(chat, 0);
        Random rnd = new Random(1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(w.write("피자왕7", d, recall, rnd, chat).text());
        return out;
    }

    @Test
    void 켜면_채팅을_되읽는_대사와_이름_부르는_대사가_섞인다() {
        LineWriter w = new LineWriter();
        w.setEcho(true);
        List<String> ls = lines(w, "피자 시켰어요", null, 20);
        ls.forEach(l -> System.out.println("  " + l));
        List<String> echo = ls.stream().filter(l -> l.startsWith("\"피자 시켰어요\"")).toList();
        assertThat(echo).isNotEmpty().allSatisfy(l -> assertThat(l).doesNotContain("피자왕7"));
        assertThat(ls).anySatisfy(l -> assertThat(l).contains("피자왕7님")); // 이름 부르기도 남는다
    }

    @Test
    void 끄면_예전처럼_이름만_긴_채팅과_기억_줄은_되읽지_않는다() {
        LineWriter off = new LineWriter();
        assertThat(lines(off, "피자 시켰어요", null, 10)).allSatisfy(l -> assertThat(l).contains("피자왕7"));

        LineWriter on = new LineWriter();
        on.setEcho(true);
        assertThat(lines(on, "오늘 저녁에 친구랑 피자 시켜 먹었는데 진짜 맛있었어요", null, 10))
                .noneMatch(l -> l.startsWith("\""));
        assertThat(lines(on, "안녕 솔라", "라면", 10)).allSatisfy(l -> assertThat(l).contains("라면 얘기 기억나요"));
    }
}
