/*
 * Copyright © 2026 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.anthropic;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the retry policy of {@link AnthropicClient} — the {@code retry-after} parsing, the
 * full-jitter backoff bounds, and the transcript-facing status descriptions. The policy was hardened
 * in response to a transient HTTP 529 ("overloaded"); these tests are the safety proof for that
 * high-blast-radius path (every API call flows through it). The seam is the package-private
 * {@code static} methods, made deterministic by an injected {@link Clock} and
 * {@link java.util.random.RandomGenerator}.
 */
class AnthropicClientTest {

    /** Our total retry budget (90s) — the ceiling on an honoured {@code retry-after}. */
    private static final long MAX_TOTAL_RETRY_MILLIS = 90_000L;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;
    private static final long BASE_BACKOFF_MILLIS = 500L;

    // ── retry-after: delta-seconds ───────────────────────────────────────────────

    @Test
    void retryAfterDeltaSeconds() {
        assertEquals(5_000L, AnthropicClient.retryAfterMillis(response("5"), Clock.systemUTC()));
        assertEquals(1_000L, AnthropicClient.retryAfterMillis(response(" 1 "), Clock.systemUTC()));
    }

    @Test
    void retryAfterZeroOrNegativeIsNoSignal() {
        assertEquals(-1L, AnthropicClient.retryAfterMillis(response("0"), Clock.systemUTC()));
        assertEquals(-1L, AnthropicClient.retryAfterMillis(response("-3"), Clock.systemUTC()));
    }

    @Test
    void retryAfterHugeValueIsBoundedNotOverflowed() {
        // A hostile very-large header must not wrap long; it is bounded to the total budget.
        long millis = AnthropicClient.retryAfterMillis(response("10000000000000000"), Clock.systemUTC());
        assertEquals(MAX_TOTAL_RETRY_MILLIS, millis);
    }

    @Test
    void retryAfterGarbageIsNoSignal() {
        assertEquals(-1L, AnthropicClient.retryAfterMillis(response("soon"), Clock.systemUTC()));
        assertEquals(-1L, AnthropicClient.retryAfterMillis(response(""), Clock.systemUTC()));
    }

    @Test
    void retryAfterAbsentIsNoSignal() {
        assertEquals(-1L, AnthropicClient.retryAfterMillis(response(null), Clock.systemUTC()));
    }

    // ── retry-after: HTTP-date ───────────────────────────────────────────────────

    @Test
    void retryAfterHttpDateInFuture() {
        Instant base = Instant.parse("2026-06-23T12:00:00Z");
        Clock clock = Clock.fixed(base, ZoneOffset.UTC);
        String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(base.plusSeconds(20), ZoneOffset.UTC));
        long millis = AnthropicClient.retryAfterMillis(response(future), clock);
        assertTrue(millis > 19_000L && millis <= 20_000L, "expected ~20s, was " + millis);
    }

    @Test
    void retryAfterHttpDateInPastIsNoSignal() {
        Instant base = Instant.parse("2026-06-23T12:00:00Z");
        Clock clock = Clock.fixed(base, ZoneOffset.UTC);
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(base.minusSeconds(20), ZoneOffset.UTC));
        assertEquals(-1L, AnthropicClient.retryAfterMillis(response(past), clock));
    }

    // ── backoff: a positive server signal is honoured verbatim ───────────────────

    @Test
    void backoffHonoursPositiveRetryAfterVerbatim() {
        Random rng = new Random(1);
        assertEquals(7_000L, AnthropicClient.backoffWaitMillis(2, 7_000L, rng));
        assertEquals(42L, AnthropicClient.backoffWaitMillis(0, 42L, rng));
    }

    // ── backoff: full-jitter exponential bounds, every reachable attempt ─────────

    @Test
    void backoffExponentialStaysWithinJitterBand() {
        Random rng = new Random(12345);
        for (int attempt = 0; attempt < 8; attempt++) {
            long cap = Math.min((long) (Math.pow(2, attempt) * BASE_BACKOFF_MILLIS), MAX_BACKOFF_MILLIS);
            long half = cap / 2;
            for (int sample = 0; sample < 2_000; sample++) {
                long wait = AnthropicClient.backoffWaitMillis(attempt, -1L, rng);
                assertTrue(wait >= half && wait <= cap,
                        "attempt " + attempt + " wait " + wait + " outside [" + half + "," + cap + "]");
            }
        }
    }

    @Test
    void backoffNonPositiveSignalFallsToExponential() {
        Random rng = new Random(7);
        // retry-after of 0 / negative must NOT collapse to a zero-wait hot retry.
        for (int sample = 0; sample < 1_000; sample++) {
            assertTrue(AnthropicClient.backoffWaitMillis(0, 0L, rng) >= BASE_BACKOFF_MILLIS / 2);
            assertTrue(AnthropicClient.backoffWaitMillis(0, -1L, rng) >= BASE_BACKOFF_MILLIS / 2);
        }
    }

    // ── status descriptions (transcript-facing) ──────────────────────────────────

    @Test
    void describeRetryableNamesTheCondition() {
        assertTrue(AnthropicClient.describeRetryable(529, 0).contains("overloaded (529)"));
        assertTrue(AnthropicClient.describeRetryable(429, 1).contains("rate-limited (429)"));
        assertTrue(AnthropicClient.describeRetryable(503, 2).contains("server error (503)"));
        assertTrue(AnthropicClient.describeRetryable(529, 0).contains("attempt 1/6"),
                "should report the 1-based attempt of the cap");
    }

    // ── test helper: an HttpResponse carrying only the headers we care about ─────

    private static HttpResponse<String> response(String retryAfter) {
        Map<String, List<String>> raw = (retryAfter == null)
                ? Map.of()
                : Map.of("retry-after", List.of(retryAfter));
        HttpHeaders headers = HttpHeaders.of(raw, (k, v) -> true);
        return new HeadersOnlyResponse(headers);
    }

    /** A minimal {@link HttpResponse} exposing only {@link #headers()}; everything else is inert. */
    private record HeadersOnlyResponse(HttpHeaders headers) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 429;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public String body() {
            return "";
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
