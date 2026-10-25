package com.example.iotlab.mqtt;

/**
 * Confirma al broker (PUBACK) un mensaje que llegó a su estado final (ADR-010). Las
 * implementaciones respetan el orden de llegada aunque se llame fuera de orden.
 */
public interface Acknowledger {

    void acknowledge(long ackToken);
}
