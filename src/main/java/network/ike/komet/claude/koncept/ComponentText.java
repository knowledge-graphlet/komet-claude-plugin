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
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.Field;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
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
 * component's least UUID. When the store has no public id for the component, the text is
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
    public static Optional<PublicId> publicId(long nid) {
        try {
            PublicId publicId = PrimitiveData.publicId(nid);
            if (publicId == null || publicId.uuidCount() == 0) {
                return Optional.empty();
            }
            return Optional.of(publicId);
        } catch (RuntimeException unresolvable) {
            return Optional.empty();
        }
    }

    /**
     * The one UUID that stands for a component where text names it by a single UUID: the least
     * of its public id's UUIDs ({@link PublicId#leastUuid()}). Any of them resolves the
     * component; the least is chosen so the text does not depend on the order the store lists
     * them in.
     *
     * @param nid the component's nid in the open store
     * @return the least UUID, or empty when the store has no public id for the nid
     */
    public static Optional<UUID> leastUuid(long nid) {
        return publicId(nid).map(PublicId::leastUuid);
    }

    /**
     * The identifier text of a component: its {@linkplain #leastUuid(long) least UUID}.
     *
     * @param nid the component's nid in the open store
     * @return the least UUID as a string, or {@link #UNIDENTIFIED} when the store has no public id
     *         for the nid; never a nid
     */
    public static String identifier(long nid) {
        return leastUuid(nid).map(UUID::toString).orElse(UNIDENTIFIED);
    }

    /**
     * The SNOMED CT identifier of a component: the value of its identifier semantic
     * ({@link KernelPattern#IDENTIFIER_PATTERN}) whose source is {@link KernelConcept#SCTID}, latest on
     * the view.
     *
     * @param view the view whose stamp coordinate selects the identifier semantic's version
     * @param nid  the component's nid in the open store
     * @return the SCTID, or empty when the component has none in this knowledge base
     */
    public static Optional<String> sctid(ViewCalculator view, long nid) {
        try {
            for (SemanticEntity<SemanticEntityVersion> semantic : EntityService.get()
                    .semanticsForComponentOfPattern(nid, KernelPattern.IDENTIFIER_PATTERN.nid()).toList()) {
                Latest<SemanticEntityVersion> latest = view.stampCalculator().latest(semantic);
                if (latest.isPresent()) {
                    String value = null;
                    EntityFacade source = null;
                    for (Object field : latest.get().fieldValues()) {
                        if (field instanceof String s) {
                            value = s;
                        } else if (field instanceof EntityFacade ef) {
                            source = ef;
                        }
                    }
                    if (value != null && source != null && source.nid() == KernelConcept.SCTID.nid()) {
                        return Optional.of(value);
                    }
                }
            }
        } catch (RuntimeException unresolvable) {
            // No resolvable SCTID; the caller falls back to the UUID.
        }
        return Optional.empty();
    }

    /**
     * An explicitly typed identifier of a component, bracketed: {@code [SCTID x]} when it has an
     * {@linkplain #sctid SCTID}, else {@code [UUID x]} with its {@linkplain #leastUuid least UUID}.
     * A component the store has no public id for is marked {@code [}{@value #UNIDENTIFIED}{@code ]}.
     *
     * @param view the view that selects the identifier semantic
     * @param nid  the component's nid in the open store
     * @return the bracketed, typed identifier; never a nid
     */
    public static String typedIdentifier(ViewCalculator view, long nid) {
        return sctid(view, nid)
                .map(sctid -> "[SCTID " + sctid + "]")
                .or(() -> leastUuid(nid).map(uuid -> "[UUID " + uuid + "]"))
                .orElse("[" + UNIDENTIFIED + "]");
    }

    /**
     * A component as text that names and identifies it: its {@linkplain #name name} followed by
     * its {@linkplain #typedIdentifier typed identifier}, as {@code Finding site [SCTID 363698007]}.
     * A reader can act on the component — draw its badge, resolve it — without a lookup by name
     * ({@code IKE-Network/ike-issues#1291}).
     *
     * @param view the view that selects the description and the identifier semantic
     * @param nid  the component's nid in the open store
     * @return the name and the bracketed identifier; never a nid
     */
    public static String identified(ViewCalculator view, long nid) {
        return name(view, nid) + ' ' + typedIdentifier(view, nid);
    }

    /**
     * The durable key of a component: every UUID of its public id, joined by commas. Resolving
     * the key through the public id reconstructs the same component under any later coordinate
     * and in any store that holds it.
     *
     * @param nid the component's nid in the open store
     * @return the comma-joined UUIDs, or empty when the store has no public id for the nid
     */
    public static Optional<String> publicIdKey(long nid) {
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
     * @return the description text; when none resolves, the {@link #identifier(long) identifier};
     *         never a nid
     */
    public static String name(ViewCalculator view, long nid) {
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
     * @return the preferred name; when none resolves, the {@link #identifier(long) identifier};
     *         never a nid
     */
    public static String preferredName(ViewCalculator view, long nid) {
        return preferredName(view, nid, 0);
    }

    /** {@link #preferredName(ViewCalculator, long)}, counting how many semantics deep it is. */
    private static String preferredName(ViewCalculator view, long nid, int depth) {
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
    private static Optional<String> semanticText(ViewCalculator view, long nid, int depth) {
        StampCalculator stamps = view.stampCalculator();
        Latest<Field<String>> description = stamps.getFieldForSemantic(
                nid, KernelConcept.TEXT_FOR_DESCRIPTION.nid(), StampCalculator.FieldCriterion.MEANING);
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
     * {@link #preferredName(ViewCalculator, long) preferred name}.
     *
     * @param view the view that selects the description
     * @param nid  the component's nid in the open store
     * @return the fully qualified name, else the preferred name, else the
     *         {@link #identifier(long) identifier}; never a nid
     */
    public static String fullyQualifiedName(ViewCalculator view, long nid) {
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
    public static Optional<String> badge(ViewCalculator view, long nid) {
        return leastUuid(nid).map(uuid -> KonceptTokens.token(KonceptTokens.Kind.UUID, uuid.toString(), label(view, nid)));
    }

    /** The view's description for a badge label, or the empty string when there is none. */
    private static String label(ViewCalculator view, long nid) {
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
