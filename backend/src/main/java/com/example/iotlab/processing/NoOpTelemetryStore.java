package com.example.iotlab.processing;

import com.example.iotlab.domain.Telemetry;
import reactor.core.publisher.Mono;

/**
 * Almacén provisorio de la fase 2: no guarda nada y responde INSERTED de inmediato. Permite
 * ejercitar todo el pipeline (validación, concurrencia, confirmación en orden) antes de tener
 * PostgreSQL. Se reemplaza en la fase 3 (ver {@code PipelineConfig}).
 */
public class NoOpTelemetryStore implements TelemetryStore {

    @Override
    public Mono<StoreResult> save(Telemetry telemetry) {
        return Mono.just(StoreResult.INSERTED);
    }
}
