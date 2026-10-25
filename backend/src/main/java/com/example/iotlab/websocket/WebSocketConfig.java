package com.example.iotlab.websocket;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

@Configuration
public class WebSocketConfig {

    /** Orden -1: antes que los controladores anotados. */
    @Bean
    HandlerMapping webSocketMapping(TelemetryWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of("/ws/telemetry", handler), -1);
    }
}
