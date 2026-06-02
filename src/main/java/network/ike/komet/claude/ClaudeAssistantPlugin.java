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
package network.ike.komet.claude;

import dev.ikm.komet.framework.KometNodeFactory;

/**
 * Module anchor for the Komet Claude Plugin.
 *
 * <p>This placeholder verifies, at build time, that the Komet plugin SPI
 * ({@link KometNodeFactory}) resolves on the module path. The real
 * {@code KometNodeFactory} implementation — a RichTextArea assistant panel
 * bound to the window's {@code ViewCalculator}, backed by a hand-rolled
 * Anthropic client and read-only graph tools — replaces this in the next
 * iteration.
 */
public final class ClaudeAssistantPlugin {

    private ClaudeAssistantPlugin() {
        // Non-instantiable anchor.
    }

    /**
     * Returns the Komet plugin service type this module will contribute via
     * {@code provides dev.ikm.komet.framework.KometNodeFactory}.
     *
     * @return the {@link KometNodeFactory} SPI class
     */
    public static Class<KometNodeFactory> pluginServiceType() {
        return KometNodeFactory.class;
    }
}
