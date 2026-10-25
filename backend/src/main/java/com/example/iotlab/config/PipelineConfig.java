package com.example.iotlab.config;

import com.example.iotlab.processing.NoOpTelemetryStore;
import com.example.iotlab.processing.TelemetryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineConfig {

    /** Fase 2: almacén provisorio. La fase 3 declara el TelemetryStore de R2DBC. */
    @Bean
    @ConditionalOnMissingBean(TelemetryStore.class)
    TelemetryStore noOpTelemetryStore() {
        return new NoOpTelemetryStore();
    }
}
