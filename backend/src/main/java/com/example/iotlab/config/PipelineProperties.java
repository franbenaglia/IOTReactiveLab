package com.example.iotlab.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pipeline A (procesamiento y persistencia).
 *
 * @param concurrency escrituras en curso a la vez en {@code flatMap}. En la fase 3 no debe
 *                    superar el tamaño del pool R2DBC: el excedente esperaría una conexión libre.
 * @param maxFutureSkew tolerancia para timestamps "del futuro" (relojes desfasados).
 * @param storeTimeout plazo por intento de escritura. Una base colgada (no caída) no devuelve
 *                     error: sin plazo, la escritura ocuparía su ranura de flatMap para siempre.
 */
@ConfigurationProperties("iotlab.pipeline")
public record PipelineProperties(
        @DefaultValue("20") int concurrency,
        @DefaultValue("-40") double temperatureMin,
        @DefaultValue("125") double temperatureMax,
        @DefaultValue("5m") Duration maxFutureSkew,
        @DefaultValue("100ms") Duration retryMinBackoff,
        @DefaultValue("10s") Duration retryMaxBackoff,
        @DefaultValue("10s") Duration shutdownTimeout,
        @DefaultValue("5s") Duration storeTimeout) {

    public PipelineProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("iotlab.pipeline.concurrency debe ser >= 1");
        }
        if (temperatureMin >= temperatureMax) {
            throw new IllegalArgumentException("iotlab.pipeline.temperature-min debe ser < temperature-max");
        }
    }
}
