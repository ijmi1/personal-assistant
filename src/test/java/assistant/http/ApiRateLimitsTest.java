package assistant.http;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitsTest {
    @Test void calculatesRetryAndResetsAtTheNextWindow() {
        var counter = new ApiRateLimits.Counter(0);
        assertThat(counter.take(2, 0)).isZero();
        assertThat(counter.take(2, 1)).isZero();
        assertThat(counter.take(2, TimeUnit.SECONDS.toNanos(59))).isEqualTo(1);
        assertThat(counter.take(2, TimeUnit.SECONDS.toNanos(60))).isZero();
    }
}
