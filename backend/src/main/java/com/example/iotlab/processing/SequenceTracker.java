package com.example.iotlab.processing;

import com.example.iotlab.domain.Telemetry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Detecta cuándo la {@code sequence} de un dispositivo retrocede: el dispositivo se reinició
 * (ADR-011). Solo informa; no rechaza nada. Se llama en el orden de llegada (antes de flatMap).
 *
 * <p>Memoria: una entrada por dispositivo visto (5.000 dispositivos ≈ unos cientos de KB).
 */
@Component
public class SequenceTracker {

    private final ConcurrentMap<String, Long> last = new ConcurrentHashMap<>();

    /** @return true si la secuencia retrocedió respecto de la última vista */
    public boolean observe(Telemetry t) {
        Long previous = last.put(t.deviceId(), t.sequence());
        return previous != null && t.sequence() < previous;
    }
}
