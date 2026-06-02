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

/**
 * Komet Claude Plugin — an in-Komet assistant panel that drives a Claude
 * dialog whose read-only tools execute in-process over the live knowledge
 * graph.
 *
 * <p>The panel is contributed as a {@link dev.ikm.komet.framework.KometNodeFactory}
 * service (see the {@code provides} clause). It is <em>outbound-only</em>: Komet
 * holds the API key and runs the Anthropic tool-use loop; Claude's tools read
 * the open knowledge base through the window's {@code ViewCalculator}. There is
 * no inbound network surface.
 */
module komet.claude {
    // Komet plugin SPI + UI host (KometNodeFactory, ExplorationNodeAbstract,
    // ViewProperties, ObservableViewNoOverride, ActivityStream).
    requires dev.ikm.komet.framework;
    // KometPreferences + PreferencesService (per-OS-user API-key storage).
    requires dev.ikm.komet.preferences;

    // Tinkar: calculators (bundled in entity), ids/UUID utils (common),
    // and the Lucene Searcher.
    requires dev.ikm.tinkar.entity;
    requires dev.ikm.tinkar.common;
    requires dev.ikm.tinkar.provider.search;

    // ImmutableList return types + Lists.immutable factory in the node factory.
    requires org.eclipse.collections.api;

    // Hand-rolled Anthropic Messages client.
    requires java.net.http;

    // JavaFX UI: controls/layout (transitively graphics+base for Platform,
    // Color, Task) and the incubator RichTextArea chat transcript.
    requires javafx.controls;
    requires jfx.incubator.richtext;

    // Markdown rendering of assistant replies into the RichTextArea.
    requires org.commonmark;

    // Vendored json4j references java.beans.Introspector (java.desktop) and
    // java.sql.Timestamp (java.sql) in serializer code paths we don't exercise
    // (we use records + Maps only). Candidate to trim so these can be dropped.
    requires java.desktop;
    requires java.sql;

    exports network.ike.komet.claude;

    // The panel contribution point. ServiceLoader instantiates the factory via
    // its public static provider() method.
    provides dev.ikm.komet.framework.KometNodeFactory
            with network.ike.komet.claude.ClaudeAssistantNodeFactory;
}
