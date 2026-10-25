package com.example.iotlab.websocket;

import java.time.Duration;
import java.util.Set;

/** Comandos del cliente (docs/websocket-contract.md). */
public sealed interface ClientCommand {

    /** @param devices vacío = todos ({@code "*"}) */
    record Subscribe(Set<String> devices, Duration interval) implements ClientCommand {
        public boolean all() { return devices.isEmpty(); }
        public boolean matches(String deviceId) { return all() || devices.contains(deviceId); }
    }

    record Ping() implements ClientCommand {}

    record Invalid(String message) implements ClientCommand {}
}
