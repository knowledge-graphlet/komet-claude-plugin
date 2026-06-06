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
 * Komet Claude Plugin — an in-Komet assistant contributed as a knowledge-layout
 * {@link dev.ikm.komet.layout.area.KlToolArea tool area} and summoned as a window in
 * the Journal workspace.
 *
 * <p>It is <em>outbound-only</em>: Komet holds the API key and runs the Anthropic
 * tool-use loop; Claude's read-only tools read the open knowledge base through the
 * journal view injected into the area. There is no inbound network surface. The area is
 * discovered via {@link java.util.ServiceLoader} (see the {@code provides} clauses):
 * {@code KlToolArea.Factory} surfaces it on the Journal "+" menu, and
 * {@code KlArea.Factory} surfaces it in the knowledge-layout editor palette.
 */
module komet.claude {
    // Knowledge-layout SPI: KlToolArea / KlSupplementalArea + SupplementalAreaBlueprint,
    // KlArea, AreaGridSettings, KlPreferencesFactory, the area lifecycle/context base.
    requires dev.ikm.komet.layout;
    // ViewProperties (the journal view the host injects into the tool area).
    requires dev.ikm.komet.framework;
    // KometPreferences + PreferencesService (per-OS-user API-key storage).
    requires dev.ikm.komet.preferences;

    // Tinkar: calculators (bundled in entity), ids/UUID utils (common),
    // and the Lucene Searcher.
    requires dev.ikm.tinkar.entity;
    requires dev.ikm.tinkar.common;
    requires dev.ikm.tinkar.provider.search;

    // slf4j: error logging routed to Komet's log4j2 backend (the image has no
    // log4j-jpl bridge, so System.Logger would miss the canonical ~/Solor/komet/logs).
    requires org.slf4j;

    // ImmutableList helpers used by the graph tools.
    requires org.eclipse.collections.api;

    // Hand-rolled Anthropic Messages client.
    requires java.net.http;

    // JavaFX UI: controls/layout (transitively graphics+base for Platform, Color)
    // and the incubator RichTextArea chat transcript.
    requires javafx.controls;
    requires jfx.incubator.richtext;

    // Markdown rendering of assistant replies into the RichTextArea.
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;

    // Vendored json4j references java.beans.Introspector (java.desktop) and
    // java.sql.Timestamp (java.sql) in serializer code paths we don't exercise
    // (we use records + Maps only). Candidate to trim so these can be dropped.
    requires java.desktop;
    requires java.sql;

    exports network.ike.komet.claude;

    // The vendored json4j (Json) discovers optional Serializer/Deserializer providers via
    // ServiceLoader in its static initializer; in a named module that REQUIRES a matching
    // `uses` declaration, or the load throws ServiceConfigurationError (an Error) on first
    // use. We register no providers, so these resolve to empty lists.
    uses network.ike.komet.claude.json.Json.Serializer;
    uses network.ike.komet.claude.json.Json.Deserializer;

    // Tool-area contribution points. ServiceLoader instantiates the factory via its
    // public no-arg constructor. KlToolArea.Factory is what the Journal workspace
    // enumerates for its "+" menu; KlArea.Factory makes the area available to the
    // knowledge-layout editor palette as well.
    provides dev.ikm.komet.layout.area.KlToolArea.Factory
            with network.ike.komet.claude.ClaudeAssistantArea.Factory;
    provides dev.ikm.komet.layout.KlArea.Factory
            with network.ike.komet.claude.ClaudeAssistantArea.Factory;
}
