package com.example.iotlab.processing;

import com.example.iotlab.config.PipelineProperties;
import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.mqtt.InboundMessage;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deserializa, valida y normaliza según docs/mqtt-contract.md. Función pura y síncrona: solo
 * CPU, sin I/O, así que puede correr en cualquier hilo, incluidos los event loops (ver
 * docs/reactor-concepts.md, {@code map}). Nunca lanza excepciones: todo error termina en
 * {@link ParseResult#invalid}, para que un mensaje malo no corte el Flux.
 *
 * <p>Se recorre el árbol JSON en lugar de mapear a una clase: así "24.5" (string) se rechaza
 * como {@code WRONG_TYPE} en vez de convertirse en silencio.
 */
@Component
public class TelemetryParser {

    static final Pattern DEVICE_ID = Pattern.compile("^[a-z0-9-]{3,64}$");

    private final ObjectMapper mapper;
    private final PipelineProperties props;
    private final Clock clock;

    @Autowired
    public TelemetryParser(ObjectMapper mapper, PipelineProperties props) {
        this(mapper, props, Clock.systemUTC());
    }

    TelemetryParser(ObjectMapper mapper, PipelineProperties props, Clock clock) {
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    /** Excepción interna para cortar la validación con un motivo. No sale de esta clase. */
    private static final class Invalid extends Exception {
        final InvalidReason reason;

        Invalid(InvalidReason reason, String detail) {
            super(detail, null, false, false); // sin stack trace: es control de flujo barato
            this.reason = reason;
        }
    }

    public ParseResult parse(InboundMessage msg) {
        String[] parts = msg.topic().split("/");
        if (parts.length != 4 || !"iot".equals(parts[0]) || !"devices".equals(parts[1])) {
            return ParseResult.invalid(msg, InvalidReason.UNKNOWN_TOPIC, msg.topic());
        }
        String topicDeviceId = parts[2];
        try {
            JsonNode root = readObject(msg.payload());
            switch (parts[3]) {
                case "telemetry":
                    return ParseResult.telemetry(msg, telemetry(root, topicDeviceId, msg.receivedAt()));
                case "status":
                    return ParseResult.status(msg, status(root, topicDeviceId, msg.receivedAt()));
                default:
                    return ParseResult.invalid(msg, InvalidReason.UNKNOWN_TOPIC, msg.topic());
            }
        } catch (Invalid e) {
            return ParseResult.invalid(msg, e.reason, e.getMessage());
        }
    }

    private JsonNode readObject(byte[] payload) throws Invalid {
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (JacksonException e) {
            throw new Invalid(InvalidReason.MALFORMED_JSON, e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new Invalid(InvalidReason.NOT_AN_OBJECT, "el payload no es un objeto JSON");
        }
        return root;
    }

    private Telemetry telemetry(JsonNode root, String topicDeviceId, Instant receivedAt) throws Invalid {
        String deviceId = deviceId(root, topicDeviceId);

        JsonNode t = required(root, "temperature");
        if (!t.isNumber()) {
            throw new Invalid(InvalidReason.WRONG_TYPE, "temperature no es numérico");
        }
        double temperature = t.doubleValue();
        if (temperature < props.temperatureMin() || temperature > props.temperatureMax()) {
            throw new Invalid(InvalidReason.OUT_OF_RANGE, "temperature=" + temperature);
        }

        Double humidity = null;
        JsonNode h = root.get("humidity");
        if (h != null && !h.isNull()) {
            if (!h.isNumber()) {
                throw new Invalid(InvalidReason.WRONG_TYPE, "humidity no es numérico");
            }
            if (h.doubleValue() < 0 || h.doubleValue() > 100) {
                throw new Invalid(InvalidReason.OUT_OF_RANGE, "humidity=" + h.doubleValue());
            }
            humidity = round(h.doubleValue(), 1);
        }

        JsonNode s = required(root, "sequence");
        if (!s.isIntegralNumber() || !s.canConvertToLong() || s.longValue() < 0) {
            throw new Invalid(InvalidReason.BAD_SEQUENCE, "sequence=" + s);
        }

        Instant measuredAt = timestamp(required(root, "timestamp"));
        if (measuredAt.isAfter(clock.instant().plus(props.maxFutureSkew()))) {
            throw new Invalid(InvalidReason.FUTURE_TIMESTAMP, "timestamp=" + measuredAt);
        }

        JsonNode src = required(root, "source");
        Source source;
        try {
            source = Source.valueOf(src.isString() ? src.stringValue() : "");
        } catch (IllegalArgumentException e) {
            throw new Invalid(InvalidReason.BAD_SOURCE, "source=" + src);
        }

        // Normalización: 2 decimales de temperatura, 1 de humedad, timestamp a milisegundos.
        // Así dos reentregas del mismo mensaje producen exactamente la misma clave (ADR-011).
        return new Telemetry(deviceId, round(temperature, 2), humidity,
                measuredAt.truncatedTo(ChronoUnit.MILLIS), s.longValue(), source, receivedAt);
    }

    private DeviceStatus status(JsonNode root, String topicDeviceId, Instant receivedAt) throws Invalid {
        String deviceId = deviceId(root, topicDeviceId);
        JsonNode st = required(root, "status");
        DeviceStatus.State state;
        if (st.isString() && "online".equals(st.stringValue())) {
            state = DeviceStatus.State.ONLINE;
        } else if (st.isString() && "offline".equals(st.stringValue())) {
            state = DeviceStatus.State.OFFLINE;
        } else {
            throw new Invalid(InvalidReason.BAD_STATUS, "status=" + st);
        }
        JsonNode ts = root.get("timestamp"); // ausente en el LWT
        Instant timestamp = ts == null || ts.isNull() ? null : timestamp(ts);
        return new DeviceStatus(deviceId, state, timestamp, receivedAt);
    }

    private String deviceId(JsonNode root, String topicDeviceId) throws Invalid {
        JsonNode id = required(root, "deviceId");
        if (!id.isString()) {
            throw new Invalid(InvalidReason.WRONG_TYPE, "deviceId no es string");
        }
        String deviceId = id.stringValue();
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new Invalid(InvalidReason.BAD_DEVICE_ID, "deviceId=" + deviceId);
        }
        if (!deviceId.equals(topicDeviceId)) {
            throw new Invalid(InvalidReason.ID_MISMATCH, deviceId + " != " + topicDeviceId);
        }
        return deviceId;
    }

    private static JsonNode required(JsonNode root, String field) throws Invalid {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            throw new Invalid(InvalidReason.MISSING_FIELD, "falta " + field);
        }
        return n;
    }

    private static Instant timestamp(JsonNode ts) throws Invalid {
        if (!ts.isString() || !ts.stringValue().endsWith("Z")) {
            throw new Invalid(InvalidReason.BAD_TIMESTAMP, "timestamp no es ISO-8601 UTC con Z");
        }
        try {
            return Instant.parse(ts.stringValue());
        } catch (DateTimeException e) {
            throw new Invalid(InvalidReason.BAD_TIMESTAMP, "timestamp=" + ts.stringValue());
        }
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
