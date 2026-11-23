package com.livecharacter.live;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsService tts;

    public TtsController(TtsService tts) {
        this.tts = tts;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("available", tts.available(), "provider", tts.provider());
    }

    @GetMapping(produces = "audio/mpeg")
    public ResponseEntity<byte[]> speak(@RequestParam("text") String text,
                                        @RequestParam(value = "role", defaultValue = "solar") String role,
                                        @RequestParam(value = "emotion", defaultValue = "HAPPY") String emotion) {
        byte[] mp3 = tts.synthesize(text, role, emotion);
        if (mp3 == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(mp3);
    }
}
