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

/**
 * Unchecked failure raised by the Zulip notify-out path — a non-2xx Zulip API
 * response, a transport error after retries, or missing configuration.
 *
 * <p>Mirrors {@link network.ike.komet.claude.anthropic.AnthropicException}: the
 * Zulip surface is outbound-only and best-effort, so callers may log and
 * continue rather than fail the curation action that triggered the notification.
 */
public final class ZulipException extends RuntimeException {

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public ZulipException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ZulipException(String message, Throwable cause) {
        super(message, cause);
    }
}
