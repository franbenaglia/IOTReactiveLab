package com.example.iotlab.domain;

import java.time.Instant;

/**
 * Estado publicado en {@code iot/devices/{id}/status}.
 *
 * @param timestamp nulo en el LWT (lo fija el dispositivo al conectar, quedaría viejo)
 */
public record DeviceStatus(String deviceId, State state, Instant timestamp, Instant receivedAt) {

    public enum State {
        ONLINE,
        OFFLINE
    }
}
