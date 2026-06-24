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
package network.ike.komet.claude.semantic;

import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The single source of the semantic lift's two authored prompt layers, shared by the headless
 * {@link SemanticLift} engine and the {@code SemanticLiftArea} UI so the two never diverge:
 *
 * <ul>
 *   <li>the <strong>fixed invariants</strong> ({@link #invariants()}) — sealed, code-owned, the
 *       read-only "system prompt"; and</li>
 *   <li>the <strong>general guidance</strong> ({@link #effectiveGuidance()}) — the user-editable
 *       "intermediate prompt", a default the user may override and which is then persisted per
 *       OS-user and applied to every lift.</li>
 * </ul>
 *
 * <p>The third layer — the per-run "session prompt" (the request) — is not authored here; it is
 * the user message passed to {@link SemanticLift#lift(String)}. The computed pattern field digest
 * is produced by {@link PatternFields#digest()}.
 */
public final class SemanticPrompts {

    /** Per-OS-user preference key holding the user's general-guidance override (empty = use default). */
    public static final String PREF_GUIDANCE = "network.ike.komet.claude.semanticLift.guidance";

    private static final String INVARIANTS_RESOURCE = "/network/ike/komet/claude/semantic-lift-invariants.md";
    private static final String GUIDANCE_RESOURCE = "/network/ike/komet/claude/semantic-lift-guidance.md";
    private static final String FALLBACK_INVARIANTS =
            "You build a structured semantic for a pattern by grounding a request against the open "
            + "knowledge base. Produce it ONLY by calling emit_semantic. Never invent an identifier; "
            + "every component reference must be one the tools returned, and emit_semantic rejects any "
            + "that does not resolve. Honor each field's datatype. You are read-only.";
    private static final String FALLBACK_GUIDANCE =
            "Work field by field, using each field's meaning, purpose, and datatype to decide what "
            + "belongs there. Fill the fields the request supports; leave the others empty.";

    private SemanticPrompts() {
    }

    /**
     * The fixed invariants — the sealed, non-editable system prompt.
     *
     * @return the invariants markdown (never null)
     */
    public static String invariants() {
        return loadResource(INVARIANTS_RESOURCE, FALLBACK_INVARIANTS);
    }

    /**
     * The shipped default general guidance, before any user override.
     *
     * @return the default guidance markdown (never null)
     */
    public static String defaultGuidance() {
        return loadResource(GUIDANCE_RESOURCE, FALLBACK_GUIDANCE);
    }

    /**
     * The general guidance in effect: the user's persisted override when present and non-blank,
     * otherwise the shipped {@link #defaultGuidance()}. Defensive against an unavailable preferences
     * backing store (returns the default).
     *
     * @return the effective guidance markdown (never null)
     */
    public static String effectiveGuidance() {
        try {
            String override = PreferencesService.userPreferences().get(PREF_GUIDANCE, "");
            if (override != null && !override.isBlank()) {
                return override;
            }
        } catch (RuntimeException preferencesUnavailable) {
            // fall through to the shipped default
        }
        return defaultGuidance();
    }

    /**
     * Persists the user's general-guidance override per OS-user. Blank text, or text equal to the
     * shipped default, clears the override (so the lift tracks future default changes).
     *
     * @param text the edited guidance markdown; null or blank clears the override
     */
    public static void saveGuidance(String text) {
        try {
            KometPreferences prefs = PreferencesService.userPreferences();
            boolean isDefault = (text == null) || text.isBlank()
                    || text.strip().equals(defaultGuidance().strip());
            prefs.put(PREF_GUIDANCE, isDefault ? "" : text);
            prefs.flush();
        } catch (Exception preferencesUnavailable) {
            // best-effort persistence (flush may throw BackingStoreException); an unavailable store
            // just means the override stays session-local
        }
    }

    /** Loads a classpath resource as UTF-8, falling back to an inline default. */
    private static String loadResource(String resource, String fallback) {
        try (InputStream in = SemanticPrompts.class.getResourceAsStream(resource)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fall through to the inline fallback
        }
        return fallback;
    }
}
