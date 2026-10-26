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

/**
 * Unchecked exception raised when an {@link LlmProvider} request fails — a
 * non-2xx response, a transport error, or an exhausted retry budget. Provider
 * implementations throw either this or a more specific subtype (for example
 * {@code network.ike.komet.claude.anthropic.AnthropicException}); callers treat
 * it as a plain {@link RuntimeException}, so the hierarchy only matters for
 * tooling and future provider-specific handling.
 */
public class LlmProviderException extends RuntimeException {

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public LlmProviderException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
