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
package network.ike.komet.claude.koncept;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.stamp.calculator.StampCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.Field;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.terms.TinkarTerm;
import network.ike.komet.claude.ui.KonceptTokens;

import java.util.Optional;
import java.util.UUID;

/**
 * The text forms of a component: its name, its identifier, its durable key, and its badge token.
 * None of them ever contains a nid ({@code IKE-Network/ike-issues#1170}).
 *
 * <p>A nid is local to one store. The assistant's text is kept — in conversation files, in the
 * prose semantics of the conversation journal, in commit comments, in Zulip messages, in exported
 * transcripts — and it is read later against a store in which the same number identifies another
 * component or none. Text therefore identifies a component only by its public id: a UUID, or the
 * badge token {@code k:uuid=<UUID>[Name]} built from it.
 *
 * <p>Two rules hold for every method here. When no description resolves, the name is the
 * component's first UUID. When the store has no public id for the component, the text is
 * {@link #UNIDENTIFIED} and has no identifier in it.
 */
public final class ComponentText {

    /** The text written for a component the store has no public id for; it has no identifier in it. */
    public static final String UNIDENTIFIED = "unidentified component";

    /**
     * How many semantics deep the text of a semantic names what it is attached to. A semantic
     * attached to a semantic attached to a concept is two deep; past the limit the attached-to
     * component is named by its identifier, so text is produced even from a store in which
     * semantics refer to each other in a circle.
     */
    private static final int MAX_SEMANTIC_DEPTH = 8;

    private ComponentText() {
    }

    /**
     * The component's public id.
     *
     * @param nid the component's nid in the open store
     * @return the public id, or empty when the store has none for the nid or it holds no UUID
     */
    public static Optional<PublicId> publicId(int nid) {
        try {
            PublicId publicId = PrimitiveData.publicId(nid);
            if (publicId == null || publicId.asUuidArray().length == 0) {
                return Optional.empty();
            }
            return Optional.of(publicId);
        } catch (RuntimeException unresolvable) {
            return Optional.empty();
        }
    }

    /**
     * The first UUID of the component's public id.
     *
     * @param nid the component's nid in the open store
     * @return the first UUID, or empty when the store has no public id for the nid
     */
    public static Optional<UUID> firstUuid(int nid) {
        return publicId(nid).map(publicId -> publicId.asUuidArray()[0]);
    }

    /**
     * The identifier text of a component: its first UUID.
     *
     * @param nid the component's nid in the open store
     * @return the first UUID as a string, or {@link #UNIDENTIFIED} when the store has no public id
     *         for the nid; never a nid
     */
    public static String identifier(int nid) {
        return firstUuid(nid).map(UUID::toString).orElse(UNIDENTIFIED);
    }

    /**
     * The durable key of a component: every UUID of its public id, joined by commas. Resolving
     * the key through the public id reconstructs the same component under any later coordinate
     * and in any store that holds it.
     *
     * @param nid the component's nid in the open store
     * @return the comma-joined UUIDs, or empty when the store has no public id for the nid
     */
    public static Optional<String> publicIdKey(int nid) {
        return publicId(nid).map(ComponentText::publicIdKey);
    }

    /**
     * The durable key of a public id: every UUID, joined by commas.
     *
     * @param publicId the public id; must hold at least one UUID
     * @return the comma-joined UUIDs
     * @throws IllegalArgumentException if the public id holds no UUID
     */
    public static String publicIdKey(PublicId publicId) {
        UUID[] uuids = publicId.asUuidArray();
        if (uuids.length == 0) {
            throw new IllegalArgumentException("A public id with no UUID has no key");
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            key.append(uuids[i]);
        }
        return key.toString();
    }

    /**
     * The name of a component under a view: the description the view's language coordinate
     * selects, which is the name the component's badge shows.
     *
     * @param view the view that selects the description
     * @param nid  the component's nid in the open store
     * @return the description text; when none resolves, the {@link #identifier(int) identifier};
     *         never a nid
     */
    public static String name(ViewCalculator view, int nid) {
        return described(view.getDescriptionText(nid)).orElseGet(() -> identifier(nid));
    }

    /**
     * The preferred name of a component under a view: its regular description, else its fully
     * qualified name, else — for a semantic, which has no description of its own — the text of
     * the semantic itself.
     *
     * <p>The text of a semantic is its description text when it is a description. Any other
     * semantic is named for what it is, {@code [meaning] of <component> for [purpose]}: the
     * meaning and purpose of its pattern, and the component it is attached to. This is the form
     * {@code LanguageCalculator.getSemanticText} writes; it is composed here because that method
     * writes the nid of any of the three that has no description.
     *
     * @param view the view that selects the description
     * @param nid  the component's nid in the open store
     * @return the preferred name; when none resolves, the {@link #identifier(int) identifier};
     *         never a nid
     */
    public static String preferredName(ViewCalculator view, int nid) {
        return preferredName(view, nid, 0);
    }

    /** {@link #preferredName(ViewCalculator, int)}, counting how many semantics deep it is. */
    private static String preferredName(ViewCalculator view, int nid, int depth) {
        return described(view.getRegularDescriptionText(nid))
                .or(() -> described(view.getFullyQualifiedNameText(nid)))
                .or(() -> semanticText(view, nid, depth))
                .orElseGet(() -> identifier(nid));
    }

    /**
     * The text of a semantic: its description text, or {@code [meaning] of <component> for
     * [purpose]}. Empty for a component that is not a semantic, for a semantic whose pattern has
     * no version under the view, and past {@link #MAX_SEMANTIC_DEPTH}.
     */
    private static Optional<String> semanticText(ViewCalculator view, int nid, int depth) {
        StampCalculator stamps = view.stampCalculator();
        Latest<Field<String>> description = stamps.getFieldForSemantic(
                nid, TinkarTerm.TEXT_FOR_DESCRIPTION.nid(), StampCalculator.FieldCriterion.MEANING);
        if (description.isPresent()) {
            Optional<String> text = described(Optional.ofNullable(description.get().value()));
            if (text.isPresent()) {
                return text;
            }
        }
        if (depth >= MAX_SEMANTIC_DEPTH) {
            return Optional.empty();
        }
        Optional<Entity<? extends EntityVersion>> entity = EntityHandle.get(nid).entity();
        if (entity.isEmpty() || !(entity.get() instanceof SemanticEntity<?> semantic)) {
            return Optional.empty();
        }
        Latest<PatternEntityVersion> pattern = stamps.latestPatternEntityVersion(semantic.patternNid());
        if (!pattern.isPresent()) {
            return Optional.empty();
        }
        return Optional.of("[" + preferredName(view, pattern.get().semanticMeaningNid(), depth + 1)
                + "] of <" + preferredName(view, semantic.referencedComponentNid(), depth + 1)
                + "> for [" + preferredName(view, pattern.get().semanticPurposeNid(), depth + 1) + "]");
    }

    /**
     * The fully qualified name of a component under a view, falling back to its
     * {@link #preferredName(ViewCalculator, int) preferred name}.
     *
     * @param view the view that selects the description
     * @param nid  the component's nid in the open store
     * @return the fully qualified name, else the preferred name, else the
     *         {@link #identifier(int) identifier}; never a nid
     */
    public static String fullyQualifiedName(ViewCalculator view, int nid) {
        return described(view.getFullyQualifiedNameText(nid)).orElseGet(() -> preferredName(view, nid));
    }

    /**
     * The badge token of a component: {@code k:uuid=<UUID>[Name]}, the form the transcript renders
     * as a badge and the model is told to write. The label is the description the view selects;
     * a component with no description gets a token without a label, and its badge still shows the
     * name the store resolves when it is rendered.
     *
     * @param view the view that selects the label; {@code null} yields a token without a label
     * @param nid  the component's nid in the open store
     * @return the badge token, or empty when the store has no public id for the nid
     */
    public static Optional<String> badge(ViewCalculator view, int nid) {
        return firstUuid(nid).map(uuid -> KonceptTokens.token(KonceptTokens.Kind.UUID, uuid.toString(), label(view, nid)));
    }

    /** The view's description for a badge label, or the empty string when there is none. */
    private static String label(ViewCalculator view, int nid) {
        if (view == null) {
            return "";
        }
        try {
            return described(view.getDescriptionText(nid)).orElse("");
        } catch (RuntimeException noDescription) {
            return "";
        }
    }

    /** A description that is present and not blank. */
    private static Optional<String> described(Optional<String> text) {
        return text.filter(value -> !value.isBlank());
    }
}
