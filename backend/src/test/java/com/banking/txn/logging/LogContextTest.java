package com.banking.txn.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogContextTest {

    @Test
    void masksAllButLastFourCharacters() {
        assertThat(LogContext.mask("ACC000123")).isEqualTo("*****0123");
        assertThat(LogContext.mask("ACC1")).isEqualTo("****");
        assertThat(LogContext.mask(null)).isNull();
    }
}
