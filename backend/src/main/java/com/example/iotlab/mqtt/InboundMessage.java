package com.example.iotlab.mqtt;

import java.time.Instant;

/**
 * Mensaje MQTT crudo, tal como llegó, más lo necesario para confirmarlo después.
 *
 * @param ackToken identificador para {@link Acknowledger}; no es el packet id de MQTT, que el
 *                 broker reutiliza
 * @param receivedNanos {@link System#nanoTime()} al recibirlo, para medir latencia
 */
public record InboundMessage(String topic, byte[] payload, long ackToken, Instant receivedAt, long receivedNanos) {}
