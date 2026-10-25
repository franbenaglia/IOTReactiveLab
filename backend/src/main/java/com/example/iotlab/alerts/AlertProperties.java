package com.example.iotlab.alerts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pipeline C (main.md §8).
 *
 * @param threshold temperatura que dispara la alerta (°C)
 * @param hysteresis la alerta se resuelve recién por debajo de {@code threshold - hysteresis}:
 *                   una temperatura que oscila alrededor del umbral no dispara y resuelve sin parar
 * @param raiseAfter mediciones seguidas por encima del umbral para disparar: un valor aislado
 *                   (un glitch del sensor) no alcanza
 */
@ConfigurationProperties("iotlab.alerts")
public record AlertProperties(
        @DefaultValue("35") double threshold,
        @DefaultValue("1") double hysteresis,
        @DefaultValue("2") int raiseAfter) {

    public AlertProperties {
        if (hysteresis < 0 || raiseAfter < 1) {
            throw new IllegalArgumentException("iotlab.alerts: hysteresis >= 0 y raise-after >= 1");
        }
    }
}
