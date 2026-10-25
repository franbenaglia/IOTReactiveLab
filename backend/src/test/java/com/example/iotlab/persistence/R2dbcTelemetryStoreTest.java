package com.example.iotlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.R2dbcBadGrammarException;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import io.r2dbc.spi.R2dbcTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Clasificación transitorio / no transitorio que decide si retryWhen insiste (ADR-010). */
class R2dbcTelemetryStoreTest {

    final R2dbcTelemetryStore store = new R2dbcTelemetryStore(null, null, null);

    @Test
    void connectionAndTimeoutErrorsAreTransient() {
        assertThat(store.isTransient(new R2dbcNonTransientResourceException("Connection refused"))).isTrue();
        assertThat(store.isTransient(new R2dbcTimeoutException("timeout", "57014"))).isTrue();
        assertThat(store.isTransient(new TimeoutException("Reactor timeout()"))).isTrue();
        assertThat(store.isTransient(new IllegalStateException("pool agotado"))).isTrue();
    }

    @Test
    void dataIntegrityAndSyntaxErrorsAreNotTransient() {
        assertThat(store.isTransient(new R2dbcDataIntegrityViolationException("check", "23514"))).isFalse();
        assertThat(store.isTransient(new R2dbcBadGrammarException("syntax", "42601"))).isFalse();
        assertThat(store.isTransient(new R2dbcDataIntegrityViolationException("numeric overflow", "22003"))).isFalse();
    }

    @Test
    void foreignKeyViolationIsTransientBecauseTheDeviceIsReRegistered() {
        assertThat(store.isTransient(new R2dbcDataIntegrityViolationException("fk", "23503"))).isTrue();
    }

    @Test
    void sqlStateIsFoundInsideSpringWrappers() {
        var wrapped = new DataIntegrityViolationException("wrapped", new R2dbcDataIntegrityViolationException("x", "23514"));
        assertThat(R2dbcTelemetryStore.sqlState(wrapped)).isEqualTo("23514");
        assertThat(store.isTransient(wrapped)).isFalse();
    }
}
