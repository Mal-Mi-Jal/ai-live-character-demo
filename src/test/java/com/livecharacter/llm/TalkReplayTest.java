package com.livecharacter.llm;

import com.livecharacter.engine.Chat;
import com.livecharacter.engine.Director;
import com.livecharacter.engine.LineWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무료: 실측 50번(2026-11-16)에서 길이로만 버려진 Haiku 대사 42줄을 새 규칙으로 다시 채점한다.
 * 같은 원문이라 "규칙만 바꾸면 얼마나 살아나나"를 볼 수 있다 (새 프롬프트로 다시 부르면 원문 자체가 달라진다 — 그건 따로 실측).
 */
class TalkReplayTest {

    @Test
    void 길이로_버려진_42줄_새_규칙으로_다시_채점() throws IOException {
        String tsv;
        try (InputStream in = getClass().getResourceAsStream("/talk-probe-2026-11-16-rejected.tsv")) {
            tsv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int total = 0, pass = 0, lens = 0;
        Map<String, Integer> why = new LinkedHashMap<>();
        for (String row : tsv.split("\\R")) {
            if (row.isBlank() || row.startsWith("#")) continue;
            String[] c = row.split("\t");
            Director.Talk t = new Director.Talk(null, List.of(
                    new Chat(1, "a", c[1], "", 0, 0, false), new Chat(2, "b", c[2], "", 0, 0, false)), "템플릿");
            HaikuLineWriter.TalkResult r = HaikuLineWriter.checkTalk(t,
                    new HaikuLineWriter.TalkOut(c[3], LineWriter.Emotion.HAPPY), Set.of(c[1], c[2]), List.of(), 0, 0, 0);
            total++;
            if ("llm".equals(r.source())) {
                pass++;
                lens += r.line().length();
                System.out.printf("  #%s 통과 (%d자 → %d자): %s%n", c[0], c[3].length(), r.line().length(), r.line());
            } else {
                why.merge(r.rejected(), 1, Integer::sum);
                System.out.printf("  #%s 버림 [%s]: %s%n", c[0], r.rejected(), c[3]);
            }
        }
        System.out.printf("다시 채점: %d줄 중 %d줄 통과 (평균 %.1f자, 읽는 시간 약 %.1f초), 버린 이유 %s%n",
                total, pass, pass == 0 ? 0.0 : (double) lens / pass, pass == 0 ? 0.0 : (double) lens / pass / 5.7, why);
        assertThat(total).isEqualTo(42);
        assertThat(pass).isGreaterThan(total / 2);
    }
}
