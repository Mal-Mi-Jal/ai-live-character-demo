package com.livecharacter.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 솔라 이야기 중 받아치기 (2026-11-23 데모 녹화에서 찾음).
 * 이야기에 반응한 채팅이 없으면 진행자는 평소처럼 아무 채팅이나 고르는데, 대사는 늘 이야기 대사를 붙여서
 * "오늘 저녁 메뉴 추천해줘?"에 "다들 너무 태연하게 들어가시길래…"(자동문 이야기)로 답했다.
 * → 이야기와 상관있는 채팅(키워드 또는 웃음·감탄 반응)에만 이야기 대사, 아니면 null = 평소 대사.
 */
class EpisodeReplyTest {

    final Director.Episode door = Director.EPISODES.get(0); // 편의점 자동문 사건

    @Test
    void 이야기와_상관없는_채팅엔_이야기_대사를_붙이지_않는다() {
        assertThat(door.title()).isEqualTo("편의점 자동문 사건");
        assertThat(door.replyFor("오늘 저녁 메뉴 추천해줘?", "치즈마법사")).isNull();
        assertThat(door.replyFor("롤 랭크 또 졌어", "망고라떼")).isNull();
    }

    @Test
    void 키워드가_맞으면_맞춤_받아치기() {
        assertThat(door.replyFor("자동문 마법이지ㅋㅋ", "star_luna")).contains("우주급 마법");
        assertThat(door.replyFor("편의점 뭐 샀어 솔라야?", "mint99")).contains("삼각김밥");
    }

    @Test
    void 웃음_감탄_반응엔_이야기를_이어_가는_예비_대사() {
        assertThat(door.replyFor("ㅋㅋㅋㅋ", "새벽감성")).contains("태연하게");
        assertThat(door.replyFor("귀여워ㅠㅠ", "새벽감성")).contains("태연하게");
    }
}
