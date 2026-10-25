package com.example.iotlab.api;

import com.example.iotlab.alerts.AlertService;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.reactive.LatestTelemetryCache;
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
    private final LatestTelemetryCache latest;
    private final AlertService alerts;

    public MetricsController(PipelineMetrics metrics, DeviceRepository devices, TelemetryRepository telemetry,
                             LatestTelemetryCache latest, AlertService alerts) {
        this.metrics = metrics;
        this.devices = devices;
        this.telemetry = telemetry;
        this.latest = latest;
        this.alerts = alerts;
    }

    /** Mono.zip: las dos consultas a la base corren concurrentes y se combinan al terminar ambas. */
    @GetMapping("/api/metrics/overview")
    public Mono<MetricsOverview> overview() {
        return Mono.zip(devices.counts(), telemetry.count())
                .map(t -> {
                    var temps = latest.latest(id -> true).stream().mapToDouble(Telemetry::temperature).summaryStatistics();
                    Double avg = temps.getCount() == 0 ? null : Math.round(temps.getAverage() * 100) / 100.0;
                    return metrics.overview(t.getT1().total(), t.getT1().online(), t.getT1().offline(), t.getT2(),
                            avg, alerts.active().size());
                });
    }
}
