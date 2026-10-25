package com.example.iotlab.mqtt;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** /actuator/health: DOWN si el backend no está conectado al broker. */
@Component("mqtt")
public class MqttHealthIndicator implements HealthIndicator {

    private final MqttSubscriber subscriber;

    public MqttHealthIndicator(MqttSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @Override
    public Health health() {
        return (subscriber.isConnected() ? Health.up() : Health.down()).build();
    }
}
