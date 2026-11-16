package com.livecharacter.engine;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * LLM이 쓴 대사를 내보내기 전에 코드가 검사한다 (Quiet Consensus의 Facts.accepts와 같은 역할).
 * 걸리면 그 줄만 템플릿 대사로 바꾼다. 어떤 검사에 걸렸는지 이유를 돌려줘서 숫자로 셀 수 있게 한다.
 */
public final class LineCheck {

    public static final int MAX_LEN = 90;

    /** 기억을 지어낼 때 쓰는 말 — 코드가 기억을 넘기지 않았는데 이런 말이 나오면 지어낸 것 */
    private static final List<String> MEMORY_WORDS = List.of("지난번", "저번", "기억");
    private static final Pattern DIGITS = Pattern.compile("\\d");

    private LineCheck() {
    }

    /**
     * @param others 같은 틱에 고른 다른 팬들의 닉네임 (다른 사람 이야기를 섞으면 안 된다)
     * @return 통과하면 null, 아니면 걸린 이유
     */
    public static String reject(String line, LiveRoom.Reaction facts, Collection<String> others) {
        if (line == null || line.isBlank()) return "빈 문장";
        if (line.length() > MAX_LEN) return "너무 김";
        if (!line.contains(facts.nickname())) return "닉네임 없음";
        for (String o : others) {
            if (!o.equals(facts.nickname()) && line.contains(o)) return "다른 팬 언급";
        }
        SafetyFilter.Result safe = SafetyFilter.check(line);
        if (safe.blocked() || !safe.text().equals(line)) return "금칙어·개인정보";
        if (facts.recall() != null) {
            if (!line.contains(facts.recall())) return "넘긴 기억을 안 씀";
        } else {
            for (String w : MEMORY_WORDS) if (line.contains(w)) return "기억 지어냄";
        }
        // 숫자는 닉네임 안에 있는 것만 허용 (금액·횟수 같은 숫자를 지어내지 않게)
        if (DIGITS.matcher(line.replace(facts.nickname(), "")).find()) return "숫자 지어냄";
        return null;
    }
}
