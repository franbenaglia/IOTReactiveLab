package com.example.iotlab.websocket;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JSON del WebSocket. Como el parser de MQTT: nunca lanza, todo error es un {@code Invalid}. */
@Component
public class WsCodec {

    static final Pattern DEVICE_ID = Pattern.compile("^[a-z0-9-]{3,64}$");
    static final int MAX_DEVICES = 500;
    static final long MIN_INTERVAL_MS = 250, MAX_INTERVAL_MS = 60_000, DEFAULT_INTERVAL_MS = 1000;

    private final ObjectMapper mapper;

    public WsCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ClientCommand parse(String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JacksonException e) {
            return new ClientCommand.Invalid("JSON inválido");
        }
        if (root == null || !root.isObject() || root.get("type") == null || !root.get("type").isString()) {
            return new ClientCommand.Invalid("se esperaba un objeto con 'type'");
        }
        switch (root.get("type").stringValue()) {
            case "ping":
                return new ClientCommand.Ping();
            case "subscribe":
                return subscribe(root);
            default:
                return new ClientCommand.Invalid("type desconocido: " + root.get("type").stringValue());
        }
    }

    private ClientCommand subscribe(JsonNode root) {
        long interval = DEFAULT_INTERVAL_MS;
        JsonNode i = root.get("intervalMs");
        if (i != null && !i.isNull()) {
            if (!i.isIntegralNumber() || i.longValue() < MIN_INTERVAL_MS || i.longValue() > MAX_INTERVAL_MS) {
                return new ClientCommand.Invalid("intervalMs debe estar entre " + MIN_INTERVAL_MS + " y " + MAX_INTERVAL_MS);
            }
            interval = i.longValue();
        }
        JsonNode d = root.get("devices");
        Set<String> devices = new HashSet<>();
        if (d != null && d.isString() && "*".equals(d.stringValue())) {
            return new ClientCommand.Subscribe(Set.of(), Duration.ofMillis(interval));
        }
        if (d == null || !d.isArray() || d.isEmpty() || d.size() > MAX_DEVICES) {
            return new ClientCommand.Invalid("devices debe ser \"*\" o una lista de 1 a " + MAX_DEVICES + " deviceId");
        }
        for (JsonNode n : d) {
            if (!n.isString() || !DEVICE_ID.matcher(n.stringValue()).matches()) {
                return new ClientCommand.Invalid("deviceId inválido: " + n);
            }
            devices.add(n.stringValue());
        }
        return new ClientCommand.Subscribe(Set.copyOf(devices), Duration.ofMillis(interval));
    }

    public String write(Object message) {
        return mapper.writeValueAsString(message);
    }
}
