package com.example.iotlab.persistence;

import com.example.iotlab.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Dos optimizaciones sobre {@code devices}, para no duplicar las escrituras por mensaje:
 *
 * <ul>
 *   <li><b>Alta una sola vez.</b> {@code telemetry.device_id} es FK: el dispositivo tiene que
 *       existir. Los ya dados de alta se recuerdan y {@link #ensure} no va a la base.
 *   <li><b>last_seen por lotes.</b> En vez de un UPDATE por mensaje (5.000 msg/s = 5.000 UPDATE
 *       sobre filas calientes), se acumula en memoria el último instante por dispositivo y
 *       {@link DeviceRegistryFlusher} lo escribe cada pocos segundos en una sola sentencia. Si
 *       el backend cae, se pierden como mucho esos segundos de last_seen; la telemetría, que es
 *       el dato verdadero, no se pierde.
 * </ul>
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final DeviceRepository devices;
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Instant> pendingSeen = new ConcurrentHashMap<>();

    public DeviceRegistry(DeviceRepository devices) {
        this.devices = devices;
    }

    /** Mono vacío si ya se conoce; si no, upsert y lo recuerda al completar. */
    public Mono<Void> ensure(Telemetry t) {
        if (known.contains(t.deviceId())) {
            return Mono.empty();
        }
        return devices.ensure(t.deviceId(), t.source()).doOnSuccess(v -> known.add(t.deviceId()));
    }

    /** Ante una violación de FK (p. ej. alguien vació devices), olvidar para volver a dar de alta. */
    public void forget(String deviceId) {
        known.remove(deviceId);
    }

    public void touch(String deviceId, Instant seenAt) {
        pendingSeen.merge(deviceId, seenAt, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Escribe y vacía los last_seen acumulados. Si falla, se reponen para el próximo intento. */
    public Mono<Long> flush() {
        return Mono.defer(() -> {
            if (pendingSeen.isEmpty()) {
                return Mono.just(0L);
            }
            Map<String, Instant> batch = new java.util.HashMap<>();
            for (String id : pendingSeen.keySet()) {
                Instant v = pendingSeen.remove(id);
                if (v != null) {
                    batch.put(id, v);
                }
            }
            String[] ids = batch.keySet().toArray(String[]::new);
            Instant[] seen = new Instant[ids.length];
            for (int i = 0; i < ids.length; i++) {
                seen[i] = batch.get(ids[i]);
            }
            return devices.touchAll(ids, seen)
                    .doOnError(e -> {
                        log.warn("No se pudo actualizar last_seen de {} dispositivos: {}", ids.length, e.toString());
                        batch.forEach(this::touch);
                    });
        });
    }

    public int pendingTouches() {
        return pendingSeen.size();
    }
}
