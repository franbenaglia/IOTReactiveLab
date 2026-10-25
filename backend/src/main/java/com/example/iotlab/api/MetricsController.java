package com.example.iotlab.api;

import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.persistence.DeviceRepository;
import com.example.iotlab.persistence.TelemetryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class MetricsController {

    private final PipelineMetrics metrics;
    private final DeviceRepository devices;
    private final TelemetryRepository telemetry;

    public MetricsController(PipelineMetrics metrics, DeviceRepository devices, TelemetryRepository telemetry) {
        this.metrics = metrics;
        this.devices = devices;
        this.telemetry = telemetry;
    }

    /** Mono.zip: las dos consultas a la base corren concurrentes y se combinan al terminar ambas. */
    @GetMapping("/api/metrics/overview")
    public Mono<MetricsOverview> overview() {
        return Mono.zip(devices.counts(), telemetry.count())
                .map(t -> metrics.overview(t.getT1().total(), t.getT1().online(), t.getT1().offline(), t.getT2()));
    }
}
