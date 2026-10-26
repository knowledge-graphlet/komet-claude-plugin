/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.provider;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/**
 * The provider-neutral transient-failure retry math shared by every {@link LlmProvider}:
 * the full-jitter exponential backoff and the {@code retry-after} header parsing.
 *
 * <p>These are pure and deterministic given their inputs, so the policy is unit-testable
 * without a network. Each provider owns its own retry <em>loop</em> (endpoint, headers,
 * exception type, status-page message differ per service) but reuses the wait calculation
 * here, so the backoff behaviour cannot drift between providers. The Anthropic client keeps
 * thin package-private wrappers over {@link #backoffWaitMillis} and {@link #retryAfterMillis}
 * so its existing tests are unaffected.
 */
public final class HttpRetry {

    /** Default retry attempts for transient (429 / 5xx / I/O) failures. */
    public static final int DEFAULT_MAX_ATTEMPTS = 6;

    /** Default base backoff; attempt {@code n} waits ~{@code 2^n} times this, with full jitter. */
    public static final long DEFAULT_BASE_BACKOFF_MILLIS = 500L;

    /** Default ceiling on a single self-chosen (exponential) backoff wait. */
    public static final long DEFAULT_MAX_BACKOFF_MILLIS = 30_000L;

    /** Default overall wall-clock budget for all backoff waits within one send. */
    public static final long DEFAULT_MAX_TOTAL_RETRY_MILLIS = 90_000L;

    private HttpRetry() {
    }

    /**
     * The wait before the next attempt, in millis. A positive {@code retryAfterMillis} (a server
     * {@code retry-after} signal) is honoured verbatim — the caller bounds it by the overall budget;
     * otherwise it is full-jitter exponential — a uniform random in {@code [cap/2, cap]} where
     * {@code cap = min(2^attempt * baseBackoffMillis, maxBackoffMillis)} — which spreads retries
     * instead of synchronising them. Pure and deterministic given {@code rng}.
     *
     * @param attempt           the zero-based attempt that just failed
     * @param retryAfterMillis  the server-requested wait in millis, or non-positive for none
     * @param baseBackoffMillis the base backoff (attempt 0 ceiling)
     * @param maxBackoffMillis  the ceiling on a single exponential backoff wait
     * @param rng               the randomness source for the jitter
     * @return the wait in millis (never negative)
     */
    public static long backoffWaitMillis(int attempt, long retryAfterMillis,
                                         long baseBackoffMillis, long maxBackoffMillis,
                                         RandomGenerator rng) {
        if (retryAfterMillis > 0) {
            return retryAfterMillis;
        }
        long cap = Math.min((long) (Math.pow(2, attempt) * baseBackoffMillis), maxBackoffMillis);
        long half = cap / 2;
        return half + rng.nextLong(half + 1);
    }

    /**
     * The server's requested wait from a {@code retry-after} header, in millis, or {@code -1} when
     * absent, non-positive, or unparseable (caller falls back to exponential backoff). Accepts the
     * delta-seconds form (bounded to {@code maxTotalRetryMillis} so a hostile large value cannot
     * overflow) and the HTTP-date form (resolved against {@code clock}; a past date yields
     * {@code -1}).
     *
     * @param response           the HTTP response
     * @param clock              the clock for resolving an HTTP-date {@code retry-after}
     * @param maxTotalRetryMillis the overall retry budget the honoured wait is bounded to
     * @return the requested wait in millis, or {@code -1}
     */
    public static long retryAfterMillis(HttpResponse<?> response, Clock clock, long maxTotalRetryMillis) {
        return response.headers().firstValue("retry-after").map(raw -> {
            String value = raw.trim();
            try {
                long seconds = Long.parseLong(value);
                return (seconds <= 0) ? -1L : Math.min(seconds, maxTotalRetryMillis / 1000L) * 1000L;
            } catch (NumberFormatException notSeconds) {
                try {
                    ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                    long millis = Duration.between(ZonedDateTime.now(clock), when).toMillis();
                    return (millis > 0) ? millis : -1L;
                } catch (RuntimeException notDate) {
                    return -1L;
                }
            }
        }).orElse(-1L);
    }
}
