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

    // Tinkar Composer: write COMMENT_PATTERN semantics (commit comments) referencing a STAMP,
    // and seed the narrator's author/module identity concepts.
    requires dev.ikm.tinkar.composer;

    // Tinkar events: CommitEvent + EvtBus (FrameworkTopics.COMMIT_TOPIC) drive the headless
    // commit narrator.
    requires dev.ikm.tinkar.events;

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

    // Shared Markdown → RichTextArea renderer (#585): MarkdownRichTextRenderer +
    // InlineDecorator + MarkdownStyledModel. Brings the incubator richtext model
    // transitively; the plugin supplies a ConceptChipInlineDecorator for grounding.
    requires dev.ikm.komet.markdown.richtext;

    // Rule engine: author Evrete rules (RulesBase) and actions (AbstractAction*)
    // contributed via the RuleProvider SPI; org.evrete.dsl for the rule annotations.
    requires dev.ikm.komet.rules;
    requires org.evrete.dsl.java;

    // LifeHash identicon (toucan) → PNG bytes for the Zulip Koncept badge upload.
    // Already in the komet runtime (framework requires it), so no extra staging.
    requires com.sparrowwallet.toucan;

    // Vendored json4j references java.beans.Introspector (java.desktop) and
    // java.sql.Timestamp (java.sql) in serializer code paths we don't exercise
    // (we use records + Maps only). Candidate to trim so these can be dropped.
    requires java.desktop;
    requires java.sql;

    exports network.ike.komet.claude;
    // The ANF lift area lives here. The layout engine's SupplementalAreaRenderer
    // instantiates the Factory reflectively (Constructor.newInstance) rather than via
    // ServiceLoader, so the package must be exported for that access to be legal —
    // exactly as network.ike.komet.claude is for the other area factories.
    exports network.ike.komet.claude.anf;

    // The vendored json4j (Json) discovers optional Serializer/Deserializer providers via
    // ServiceLoader in its static initializer; in a named module that REQUIRES a matching
    // `uses` declaration, or the load throws ServiceConfigurationError (an Error) on first
    // use. We register no providers, so these resolve to empty lists.
    uses network.ike.komet.claude.json.Json.Serializer;
    uses network.ike.komet.claude.json.Json.Deserializer;

    // The Claude Assistant is contributed as a first-class CARD (a KlCardProvider): the Journal
    // discovers it for the "+" menu and hosts it natively in a CardKlWindow — its own chrome and
    // sandboxed per-instance prefs-node storage — rather than inside a generic tool host.
    // ServiceLoader instantiates the provider via its public no-arg constructor.
    provides dev.ikm.komet.layout_engine.host.KlCardProvider
            with network.ike.komet.claude.ClaudeCard.Factory;
    // KlArea.Factory makes the embeddable areas available in the knowledge-layout editor palette.
    provides dev.ikm.komet.layout.KlArea.Factory
            with network.ike.komet.claude.ClaudeCheckArea.Factory,
                 network.ike.komet.claude.ChatArea.Factory,
                 network.ike.komet.claude.anf.AnfArea.Factory;
    // Placeable supplemental areas surfaced in the knowledge-layout editor's "Controls" palette.
    provides dev.ikm.komet.layout.area.KlSupplementalArea.Factory
            with network.ike.komet.claude.ClaudeCheckArea.Factory,
                 network.ike.komet.claude.ChatArea.Factory,
                 network.ike.komet.claude.anf.AnfArea.Factory;

    // Plugin-contributed Evrete rules (discovered by EvreteRulesService via the
    // RuleProvider SPI): a "Post state + history to Zulip" component-focus rule.
    // Living in the plugin, the rule + action update without a new komet release (#620).
    provides dev.ikm.komet.framework.rulebase.RuleProvider
            with network.ike.komet.claude.zulip.rules.ZulipRuleProvider;

    // Headless commit narrator: started once at app startup (after datastore load) by the
    // ServiceLifecycle machinery, regardless of whether the assistant UI is opened.
    provides dev.ikm.tinkar.common.service.ServiceLifecycle
            with network.ike.komet.claude.narrator.CommitNarratorLifecycle;

    // Evrete accesses the rule class via MethodHandles. Like komet/rules (an
    // `open module`), the rule package must be opened UNQUALIFIED — a qualified
    // `opens … to org.evrete.*` is NOT sufficient for Evrete's lookup (otherwise a
    // runtime IllegalAccessException unreflecting the rule method breaks the engine).
    opens network.ike.komet.claude.zulip.rules;
}
