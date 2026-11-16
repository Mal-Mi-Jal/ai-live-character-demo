package com.livecharacter.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * /topic/chat  : 새 채팅 묶음 (0.5초마다)
 * /topic/tick  : 캐릭터 반응 + 숫자 (틱마다)
 * /app/chat    : 브라우저에서 사람이 보내는 채팅
 * /app/control : 규칙·봇 수 바꾸기
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        // 같은 연결에서 온 메시지는 보낸 순서대로 처리한다. 없으면 여러 스레드가 나눠 처리해서
        // 후원 3개를 연달아 보냈을 때 2 → 3 → 1 순서로 대기열에 들어갔다 (2026-11-17 화면 확인 중 발견)
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }
}
