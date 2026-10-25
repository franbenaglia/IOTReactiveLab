package com.example.iotlab.alerts;

import java.util.Comparator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    /** Alertas activas, la más reciente primero. */
    @GetMapping("/api/alerts")
    public Flux<Alert> active() {
        return Flux.fromIterable(alerts.active()).sort(Comparator.comparing(Alert::at).reversed());
    }
}
