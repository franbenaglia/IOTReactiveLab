package com.example.iotlab.alerts;

import com.example.iotlab.domain.Telemetry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Máquina de estados por dispositivo, con antirrebote: {@code raiseAfter} mediciones seguidas
 * sobre el umbral para disparar, y histéresis para resolver. Mientras la alerta está activa no se
 * emite nada: es la "política para no emitir continuamente la misma alerta" de main.md §8.
 *
 * <p><b>No es thread-safe, y no necesita serlo:</b> se usa dentro de un único Flux, y Reactive
 * Streams garantiza que {@code onNext} nunca se llama en paralelo. Un {@code HashMap} común
 * alcanza.
 */
public final class AlertDetector {

    private static final class State {
        int above;
        boolean active;
    }

    private final AlertProperties props;
    private final Map<String, State> states = new HashMap<>();

    public AlertDetector(AlertProperties props) {
        this.props = props;
    }

    public Optional<Alert> evaluate(Telemetry t) {
        State s = states.computeIfAbsent(t.deviceId(), k -> new State());
        double temp = t.temperature();
        if (!s.active) {
            if (temp > props.threshold()) {
                if (++s.above >= props.raiseAfter()) {
                    s.active = true;
                    return Optional.of(alert(t, Alert.State.RAISED));
                }
            } else {
                s.above = 0;
            }
        } else if (temp < props.threshold() - props.hysteresis()) {
            s.active = false;
            s.above = 0;
            return Optional.of(alert(t, Alert.State.CLEARED));
        }
        return Optional.empty();
    }

    private Alert alert(Telemetry t, Alert.State state) {
        return new Alert(t.deviceId(), Alert.Kind.HIGH_TEMPERATURE, state, t.temperature(), props.threshold(), t.measuredAt());
    }
}
