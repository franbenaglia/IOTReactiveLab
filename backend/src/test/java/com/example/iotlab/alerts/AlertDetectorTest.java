package com.example.iotlab.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertDetectorTest {

    final AlertDetector detector = new AlertDetector(new AlertProperties(35, 1, 2));
    long seq;

    Telemetry t(String id, double temp) {
        return new Telemetry(id, temp, null, Instant.now(), ++seq, Source.SIMULATED, Instant.now());
    }

    List<Alert.State> run(String id, double... temps) {
        List<Alert.State> out = new ArrayList<>();
        for (double v : temps) {
            detector.evaluate(t(id, v)).ifPresent(a -> out.add(a.state()));
        }
        return out;
    }

    @Test
    void raisesAfterConsecutiveSamplesAboveThreshold() {
        assertThat(run("d1", 30, 36, 37)).containsExactly(Alert.State.RAISED);
    }

    @Test
    void anIsolatedSpikeDoesNotRaise() {
        assertThat(run("d1", 30, 40, 30, 41, 30)).isEmpty();
    }

    @Test
    void doesNotRepeatWhileStillAboveThreshold() {
        assertThat(run("d1", 36, 36, 37, 38, 39, 40)).containsExactly(Alert.State.RAISED);
    }

    @Test
    void clearsOnlyBelowThresholdMinusHysteresis() {
        // 34.5 está debajo del umbral pero dentro de la histéresis (35 − 1 = 34): sigue activa.
        assertThat(run("d1", 36, 36, 34.5, 35.5, 34.5, 33.9)).containsExactly(Alert.State.RAISED, Alert.State.CLEARED);
    }

    @Test
    void oscillationAroundThresholdDoesNotFlap() {
        assertThat(run("d1", 36, 36, 34.8, 35.2, 34.8, 35.2, 34.8)).containsExactly(Alert.State.RAISED);
    }

    @Test
    void devicesAreIndependent() {
        assertThat(run("a", 36)).isEmpty();
        assertThat(run("b", 36)).isEmpty();   // la de b no completa la racha de a
        assertThat(run("a", 36)).containsExactly(Alert.State.RAISED);
    }
}
