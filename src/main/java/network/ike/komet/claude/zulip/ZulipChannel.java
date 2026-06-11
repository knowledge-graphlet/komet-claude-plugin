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
package network.ike.komet.claude.zulip;

import java.util.Objects;

/**
 * A typed Zulip destination: a stream and a topic within it. This is the
 * transport-level address a {@link ZulipClient} posts to — deliberately a value
 * type rather than a pair of bare strings, so the Subject-to-channel mapping is
 * visible to the compiler.
 *
 * <p>Zulip caps topic length at 60 characters; {@link #of(String, String)}
 * truncates to keep the topic valid.
 *
 * @param stream the stream name (Zulip channel)
 * @param topic  the topic within the stream (at most {@value #MAX_TOPIC} chars)
 */
public record ZulipChannel(String stream, String topic) {

    /** Zulip's maximum topic length. */
    public static final int MAX_TOPIC = 60;

    /**
     * Validates the channel.
     *
     * @throws NullPointerException     if a field is null
     * @throws IllegalArgumentException if a field is blank or the topic is too long
     */
    public ZulipChannel {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(topic, "topic");
        if (stream.isBlank()) {
            throw new IllegalArgumentException("stream must not be blank");
        }
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (topic.length() > MAX_TOPIC) {
            throw new IllegalArgumentException("topic exceeds " + MAX_TOPIC + " chars: " + topic);
        }
    }

    /**
     * Creates a channel, truncating an over-long topic to {@value #MAX_TOPIC}
     * characters (with an ellipsis) so callers need not pre-trim.
     *
     * @param stream the stream name
     * @param topic  the desired topic; truncated if it exceeds the limit
     * @return the channel
     */
    public static ZulipChannel of(String stream, String topic) {
        String t = Objects.requireNonNull(topic, "topic").strip();
        if (t.length() > MAX_TOPIC) {
            t = t.substring(0, MAX_TOPIC - 1).strip() + "…";
        }
        return new ZulipChannel(stream, t);
    }
}
