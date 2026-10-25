package com.example.iotlab.processing;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Telemetry;
import reactor.core.publisher.Mono;

/**
 * Almacén que no guarda nada y responde INSERTED de inmediato. Fue el de la fase 2; hoy sirve para
 * pruebas del pipeline sin base y como respaldo si no hay otro {@link TelemetryStore} (ver {@code
 * PipelineConfig}).
 */
public class NoOpTelemetryStore implements TelemetryStore {

    @Override
    public Mono<StoreResult> save(Telemetry telemetry) {
        return Mono.just(StoreResult.INSERTED);
    }

    @Override
    public Mono<Void> saveStatus(DeviceStatus status) {
        return Mono.empty();
    }
}
