package com.example.iotlab.config;

import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activa las métricas de Reactor Netty del servidor HTTP (conexiones activas, bytes, tiempos y el
 * allocator de buffers). Vienen apagadas por defecto.
 *
 * <p>El segundo argumento agrupa las URIs: sin él habría una serie por cada
 * /api/devices/{deviceId} distinto (alta cardinalidad, main.md §16).
 */
@Configuration
public class NettyMetricsConfig {

    @Bean
    NettyServerCustomizer nettyMetrics() {
        return server -> server.metrics(true, NettyMetricsConfig::uriTemplate);
    }

    static String uriTemplate(String uri) {
        if (uri.startsWith("/api/devices/")) {
            return "/api/devices/{id}/**";
        }
        if (uri.startsWith("/actuator/")) {
            return "/actuator/**";
        }
        return uri.startsWith("/api/") || uri.startsWith("/ws/") ? uri : "/**";
    }
}
