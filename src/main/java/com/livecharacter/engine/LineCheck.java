package com.livecharacter.engine;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * LLM이 쓴 대사를 내보내기 전에 코드가 검사한다 (Quiet Consensus의 Facts.accepts와 같은 역할).
 * 걸리면 그 줄만 템플릿 대사로 바꾼다. 어떤 검사에 걸렸는지 이유를 돌려줘서 숫자로 셀 수 있게 한다.
 */
public final class LineCheck {

    /**
     * 음성으로 읽힐 대사라 짧게. 초당 5.7자(Windows 한국어 음성 실측) → 30자 약 5초, 45자 약 8초.
     * 실측(2026-11-16): 90자 제한 → Haiku 평균 65자(11.4초). 35자 제한 → 20줄 중 18줄이 넘어서 버려짐
     * → 프롬프트 목표는 30자, 버리는 선은 45자로 벌려 둠 (다음 측정에서 실제 길이를 보고 다시 정한다).
     * (예전 90자 제한에서는 81~90자 대사가 SafetyFilter의 80자 자르기에 걸려 "금칙어·개인정보"로 잘못 셌다)
     */
    public static final int TARGET_LEN = 30;
    public static final int MAX_LEN = 45;

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
        String lore = loreBreak(line);
        if (lore != null) return lore;
        return null;
    }

    /** 이어 말하기는 2~3문장이라 더 길게 허용 (초당 5.7자 → 약 14초) */
    public static final int TALK_MAX_LEN = 80;

    /**
     * 설정표와 모순되는 경험. 솔라는 지구에 막 왔다 → 지구 음식을 먹어 봤다거나 게임을 해 봤다고 하면 안 된다.
     * 실측(2026-11-16)에서 "아이스크림 먹어 봤는데"가 나왔다. 단어 목록이라 다 못 잡는다 (한계)
     */
    private static final List<Pattern> LORE_BREAKS = List.of(
            // "못 먹어 봤는데"(설정과 맞음)는 빼고 "먹어 봤는데"만 — 실측 50번에서 부정형이 자주 나와 단순 단어 비교는 오탐이었다
            Pattern.compile("(?<![못안] ?)먹어 ?봤"),
            Pattern.compile("솔라(도|가) 먹었"),
            Pattern.compile("먹어 본 적 있"),
            Pattern.compile("(?<![못안] ?)해 ?봤는데"),
            Pattern.compile("솔라도 해 ?봤"));

    /**
     * 긴 대사를 문장 단위로 줄인다: 앞에서부터 완성된 문장만, max 글자 안에 들어가는 만큼.
     * 문장을 중간에서 자르지 않는다 (말이 끊기면 음성에서 더 어색하다). 한 문장도 안 들어가면 원문 그대로 (→ 검사에서 버려짐).
     * 실측(2026-11-16, 이어 말하기 50번): "60자 안팎"을 요청해도 82~127자가 나와 42번이 길이로만 버려졌는데,
     * 대부분 앞 1~2문장만으로 충분했다.
     */
    public static String trimToSentences(String line, int max) {
        if (line == null || line.length() <= max) return line;
        String[] parts = line.split("(?<=[.!?…~])\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String next = sb.isEmpty() ? p : sb + " " + p;
            if (next.length() > max) break;
            sb.setLength(0);
            sb.append(next);
        }
        return sb.isEmpty() ? line : sb.toString();
    }

    static String loreBreak(String line) {
        for (Pattern p : LORE_BREAKS) if (p.matcher(line).find()) return "설정 모순";
        return null;
    }

    /**
     * 이어 말하기 검사. 코드가 고른 팬 닉네임만 나올 수 있고, 하나 이상은 꼭 나와야 한다.
     * @param allowed 고른 채팅의 닉네임
     * @param others  최근 채팅한 다른 팬 닉네임 (나오면 안 됨)
     */
    public static String rejectTalk(String line, Collection<String> allowed, Collection<String> others) {
        if (line == null || line.isBlank()) return "빈 문장";
        if (line.length() > TALK_MAX_LEN) return "너무 김";
        if (!allowed.isEmpty() && allowed.stream().noneMatch(line::contains)) return "닉네임 없음";
        for (String o : others) {
            if (!allowed.contains(o) && line.contains(o)) return "다른 팬 언급";
        }
        SafetyFilter.Result safe = SafetyFilter.check(line);
        if (safe.blocked() || !safe.text().equals(line)) return "금칙어·개인정보";
        for (String w : MEMORY_WORDS) if (line.contains(w)) return "기억 지어냄";
        String rest = line;
        for (String a : allowed) rest = rest.replace(a, "");
        if (DIGITS.matcher(rest).find()) return "숫자 지어냄";
        return loreBreak(line);
    }
}
