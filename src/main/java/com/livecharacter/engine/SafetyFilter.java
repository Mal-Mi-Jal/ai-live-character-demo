package com.livecharacter.engine;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 채팅이 방에 들어오기 전에 코드로 거른다 (LLM에 맡기지 않는다).
 * - 전화번호·이메일 같은 개인정보는 가린 채로 보관 → 캐릭터가 읽어 줄 일도, 기억에 남을 일도 없다
 * - 금칙어가 있으면 채팅창에는 가려서 보이지만 캐릭터가 반응할 후보에서는 뺀다
 */
public final class SafetyFilter {

    private static final Pattern PHONE = Pattern.compile("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    /** 데모용 작은 목록. 실제 서비스라면 외부 사전 + 운영자가 고치는 목록 */
    private static final List<String> BANNED = List.of("바보", "멍청", "꺼져", "죽어");

    public static final int MAX_LEN = 80;

    public record Result(String text, boolean blocked) {
    }

    private SafetyFilter() {
    }

    public static Result check(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
        t = PHONE.matcher(t).replaceAll("(번호 가림)");
        t = EMAIL.matcher(t).replaceAll("(메일 가림)");
        boolean blocked = false;
        for (String w : BANNED) {
            if (t.contains(w)) {
                blocked = true;
                t = t.replace(w, "*".repeat(w.length()));
            }
        }
        return new Result(t, blocked);
    }
}
