package com.livecharacter.engine;

/**
 * 시청자 채팅 한 줄. 서버가 받은 순간(receivedAtMs)을 기록해 두고,
 * 캐릭터가 반응한 순간과의 차이를 "채팅 → 반응 지연"으로 잰다.
 *
 * @param seq          방에 들어온 순서 (1부터)
 * @param gift         선물 금액 (0이면 일반 채팅)
 * @param human        브라우저에서 사람이 직접 보낸 채팅이면 true (봇과 구분해 화면에 표시)
 */
public record Chat(long seq, String viewerId, String nickname, String text, int gift,
                   long receivedAtMs, boolean human) {
}
