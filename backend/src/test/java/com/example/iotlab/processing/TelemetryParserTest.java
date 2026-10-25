package com.example.iotlab.processing;

import static com.example.iotlab.support.Messages.inbound;
import static com.example.iotlab.support.Messages.telemetry;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.iotlab.config.PipelineProperties;
import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TelemetryParserTest {

    static final PipelineProperties PROPS = new PipelineProperties(20, -40, 125, Duration.ofMinutes(5),
            Duration.ofMillis(100), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5));
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:31:00Z"), ZoneOffset.UTC);
    final TelemetryParser parser = new TelemetryParser(JsonMapper.builder().build(), PROPS, CLOCK);

    static final String TOPIC = "iot/devices/esp32-sim-00001/telemetry";

    @Test
    void parsesValidTelemetry() {
        ParseResult r = parser.parse(telemetry("esp32-sim-00001", 24.837, 7));
        assertThat(r.kind()).isEqualTo(ParseResult.Kind.TELEMETRY);
        Telemetry t = r.telemetry();
        assertThat(t.deviceId()).isEqualTo("esp32-sim-00001");
        assertThat(t.temperature()).isEqualTo(24.84); // normalizado a 2 decimales
        assertThat(t.humidity()).isNull();
        assertThat(t.sequence()).isEqualTo(7);
        assertThat(t.source()).isEqualTo(Source.SIMULATED);
        assertThat(t.measuredAt()).isEqualTo(Instant.parse("2026-10-25T12:30:00.123Z"));
        assertThat(t.receivedAt()).isEqualTo(Instant.parse("2026-10-25T12:30:01Z"));
    }

    @Test
    void parsesPhysicalWithHumidityAndSecondsPrecision() {
        String json = "{\"deviceId\":\"esp32-real-001\",\"temperature\":24,\"humidity\":41.04,"
                + "\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"PHYSICAL\"}";
        Telemetry t = parser.parse(inbound("iot/devices/esp32-real-001/telemetry", json)).telemetry();
        assertThat(t.humidity()).isEqualTo(41.0);
        assertThat(t.source()).isEqualTo(Source.PHYSICAL);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        // caso | payload | motivo esperado
        "JSON truncado (simulador malformed_json) | {\"deviceId\":\"esp32-sim-00001\",\"tempera | MALFORMED_JSON",
        "array en vez de objeto | [1,2] | NOT_AN_OBJECT",
        "falta temperature (missing_field) | {\"deviceId\":\"esp32-sim-00001\",\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | MISSING_FIELD",
        "temperatura 999.9 (out_of_range) | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":999.9,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | OUT_OF_RANGE",
        "temperatura string (wrong_type) | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":\"caliente\",\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | WRONG_TYPE",
        "temperatura numérica como string | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":\"24.5\",\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | WRONG_TYPE",
        "deviceId distinto al topic (id_mismatch) | {\"deviceId\":\"esp32-sim-00001-x\",\"temperature\":20,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | ID_MISMATCH",
        "deviceId con mayúsculas | {\"deviceId\":\"ESP32\",\"temperature\":20,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | BAD_DEVICE_ID",
        "humedad fuera de rango | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"humidity\":120,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | OUT_OF_RANGE",
        "sequence negativa | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":-1,\"source\":\"SIMULATED\"} | BAD_SEQUENCE",
        "sequence decimal | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1.5,\"source\":\"SIMULATED\"} | BAD_SEQUENCE",
        "timestamp con offset | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"timestamp\":\"2026-10-25T12:30:00+01:00\",\"sequence\":1,\"source\":\"SIMULATED\"} | BAD_TIMESTAMP",
        "timestamp basura | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"timestamp\":\"ayerZ\",\"sequence\":1,\"source\":\"SIMULATED\"} | BAD_TIMESTAMP",
        "timestamp a 1 hora en el futuro | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"timestamp\":\"2026-10-25T13:31:00Z\",\"sequence\":1,\"source\":\"SIMULATED\"} | FUTURE_TIMESTAMP",
        "source desconocida | {\"deviceId\":\"esp32-sim-00001\",\"temperature\":20,\"timestamp\":\"2026-10-25T12:30:00Z\",\"sequence\":1,\"source\":\"OTRO\"} | BAD_SOURCE",
    })
    void rejectsInvalidTelemetry(String caso, String payload, InvalidReason expected) {
        ParseResult r = parser.parse(inbound(TOPIC, payload));
        assertThat(r.kind()).isEqualTo(ParseResult.Kind.INVALID);
        assertThat(r.reason()).isEqualTo(expected);
    }

    @Test
    void rejectsUnknownTopics() {
        assertThat(parser.parse(inbound("otra/cosa", "{}")).reason()).isEqualTo(InvalidReason.UNKNOWN_TOPIC);
        assertThat(parser.parse(inbound("iot/devices/x01/config", "{}")).reason()).isEqualTo(InvalidReason.UNKNOWN_TOPIC);
    }

    @Test
    void parsesStatusAndWill() {
        DeviceStatus online = parser.parse(inbound("iot/devices/esp32-sim-00001/status",
                "{\"deviceId\":\"esp32-sim-00001\",\"status\":\"online\",\"timestamp\":\"2026-10-25T12:30:00Z\"}")).status();
        assertThat(online.state()).isEqualTo(DeviceStatus.State.ONLINE);
        assertThat(online.timestamp()).isNotNull();
        DeviceStatus will = parser.parse(inbound("iot/devices/esp32-sim-00001/status",
                "{\"deviceId\":\"esp32-sim-00001\",\"status\":\"offline\"}")).status();
        assertThat(will.state()).isEqualTo(DeviceStatus.State.OFFLINE);
        assertThat(will.timestamp()).isNull(); // LWT sin timestamp
        assertThat(parser.parse(inbound("iot/devices/esp32-sim-00001/status",
                "{\"deviceId\":\"esp32-sim-00001\",\"status\":\"zombie\"}")).reason()).isEqualTo(InvalidReason.BAD_STATUS);
    }

    @Test
    void sameMessageTwiceProducesTheSameKey() {
        Telemetry a = parser.parse(telemetry("esp32-sim-00001", 24.5, 3)).telemetry();
        Telemetry b = parser.parse(telemetry("esp32-sim-00001", 24.5, 3)).telemetry();
        assertThat(a.measuredAt()).isEqualTo(b.measuredAt());
        assertThat(a.sequence()).isEqualTo(b.sequence());
    }
}
