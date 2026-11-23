package com.livecharacter.live;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 무료 Microsoft Edge 뉴럴 음성(edge-tts)을 백엔드에서 mp3로 합성해 내려준다.
 *
 * 핵심 설계:
 *  - 서버가 /topic/speak 를 내보내기 전에 미리 prepare()로 합성·캐시해 두고,
 *    실제 생성된 mp3 바이트 길이(48kbps CBR = 6,000 bytes/sec)로 정확한 발화 시간(durationMs)을 잰다.
 *  - 덕분에 브라우저가 /api/tts 를 부를 때 캐시에서 즉시(<5ms) 내려가고,
 *    자막·진행 바·다음 차례 타이머가 실제 음성 끝나는 시점과 정확히 맞물려 대사가 중간에 끊기지 않는다.
 */
@Service
public class TtsService {

    public record Prepared(byte[] audio, long durationMs) {
    }

    private static final int MAX_CACHE = 300;
    private static final int MAX_TEXT_LEN = 200;
    private static final String SOLAR_VOICE = "ko-KR-SunHiNeural";
    private static final String SYSTEM_VOICE = "ko-KR-HyunsuMultilingualNeural";
    /** edge-tts 출력은 24kHz 48kbps CBR mp3 = 정확히 초당 6,000 바이트 */
    private static final double BYTES_PER_MS = 6.0;

    private final Map<String, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    private volatile boolean available;

    @PostConstruct
    void probe() {
        try {
            Process p = new ProcessBuilder("python", "-m", "edge_tts", "--version")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(4, TimeUnit.SECONDS);
            available = done && p.exitValue() == 0;
        } catch (Exception e) {
            available = false;
        }
    }

    public boolean available() {
        return available;
    }

    public String provider() {
        return available ? "Edge Neural TTS (" + SOLAR_VOICE + ")" : "browser";
    }

    /** 서버 차례 진행 전에 미리 합성·캐시하고 실제 재생 길이(ms)를 돌려준다. 실패 시 null */
    public Prepared prepare(String rawText, String role, String emotion) {
        byte[] audio = synthesize(rawText, role, emotion);
        if (audio == null) return null;
        long ms = Math.max(800, Math.round(audio.length / BYTES_PER_MS));
        return new Prepared(audio, ms);
    }

    /** 텍스트를 mp3 바이트로 합성한다. 일시적 네트워크 오류 시 1회 재시도한다. */
    public byte[] synthesize(String rawText, String role, String emotion) {
        if (!available || rawText == null || rawText.isBlank()) return null;
        String text = cleanForSpeech(rawText);
        if (text.isEmpty()) return null;
        if (text.length() > MAX_TEXT_LEN) text = text.substring(0, MAX_TEXT_LEN);

        boolean sys = "system".equalsIgnoreCase(role);
        String voice = sys ? SYSTEM_VOICE : SOLAR_VOICE;
        String[] prosody = sys ? new String[]{"+0Hz", "+5%"} : prosodyOf(emotion);
        String key = voice + "|" + prosody[0] + "|" + prosody[1] + "|" + text;

        byte[] hit = cache.get(key);
        if (hit != null) return hit;

        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] audio = runEdgeTts(voice, prosody[0], prosody[1], text);
            if (audio != null) {
                cache.put(key, audio);
                return audio;
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private byte[] runEdgeTts(String voice, String pitch, String rate, String text) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("sola-tts-", ".mp3");
            Process p = new ProcessBuilder(
                    "python", "-m", "edge_tts",
                    "--voice", voice,
                    "--pitch=" + pitch,
                    "--rate=" + rate,
                    "--text", text,
                    "--write-media", tmp.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(9, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            byte[] audio = Files.readAllBytes(tmp);
            return audio.length < 200 ? null : audio;
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 뉴럴 보코더에 과도한 피치 시프트(+10Hz 이상)를 주면 금속성 로봇 음색이 생기므로
     * 자연스러운 범위(-1Hz ~ +5Hz) 안에서 속도 위주로 감정 톤을 조절한다.
     */
    private static String[] prosodyOf(String emotion) {
        if (emotion == null) return new String[]{"+3Hz", "+4%"};
        return switch (emotion.toUpperCase()) {
            case "EXCITED" -> new String[]{"+5Hz", "+8%"};
            case "TOUCHED" -> new String[]{"+2Hz", "-2%"};
            case "THINKING" -> new String[]{"+0Hz", "-3%"};
            case "CALM" -> new String[]{"-1Hz", "-5%"};
            default -> new String[]{"+3Hz", "+4%"};
        };
    }

    /** TTS가 이모지·특수문자를 어색하게 읽거나 에러를 내지 않도록 다듬는다 */
    private static String cleanForSpeech(String text) {
        return text
                .replace("ㅎㅎ", "하하")
                .replace("ㅋㅋㅋ", "크크크")
                .replace("ㅋㅋ", "크크")
                .replace("ㅠ", "")
                .replace("ㅜ", "")
                .replace("\"", "")
                .replace("…", ", ")
                .replaceAll("[\\p{So}\\p{Cn}]", "")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
