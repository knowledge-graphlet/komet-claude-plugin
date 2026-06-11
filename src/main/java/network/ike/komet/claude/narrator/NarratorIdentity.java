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
package network.ike.komet.claude.narrator;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.composer.Composer;
import dev.ikm.tinkar.composer.Session;
import dev.ikm.tinkar.composer.assembler.ConceptAssembler;
import dev.ikm.tinkar.composer.template.FullyQualifiedName;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.State;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The narrator's machine-author identity in the graph.
 * <p>
 * Commit comments authored by the narrator must read as unambiguously machine-authored, so the
 * narrator commits as its own dedicated author concept ({@link #NARRATOR_AUTHOR}) on its own
 * module ({@link #NARRATION_MODULE}) — never as a human user. Both concepts have stable,
 * deterministic (UUID5) public ids so every machine and re-run agrees on the same identity, and
 * are seeded once, idempotently, the first time the narrator starts on a datastore that lacks them.
 * <p>
 * The author/module pair is also the key the narrator uses to recognize — and skip — its own
 * comment commits (see the commit narrator's worthiness guard).
 */
public final class NarratorIdentity {

    private static final Logger LOG = LoggerFactory.getLogger(NarratorIdentity.class);

    /** Fixed namespace for the narrator's deterministic (UUID5) identity ids. */
    private static final UUID NAMESPACE = UUID.fromString("a7d3e8f0-2b14-4c96-9e5a-1f0c8b2d6e34");

    private static final String NARRATOR_AUTHOR_NAME = "Komet Narrator (Claude)";
    private static final String NARRATION_MODULE_NAME = "Narration";

    /** The author concept under which the narrator commits its comments. */
    public static final Concept NARRATOR_AUTHOR =
            Concept.make(PublicIds.of(UuidT5Generator.get(NAMESPACE, "komet-narrator-author")));

    /** The module concept the narrator's comment stamps are recorded on. */
    public static final Concept NARRATION_MODULE =
            Concept.make(PublicIds.of(UuidT5Generator.get(NAMESPACE, "komet-narration-module")));

    /** The path the narrator's comment stamps are recorded on (the default development path). */
    public static final Concept NARRATION_PATH = TinkarTerm.DEVELOPMENT_PATH;

    private NarratorIdentity() {}

    /**
     * Seeds the narrator author and narration module concepts if they are not already present in
     * the open datastore. Idempotent — safe to call on every startup. Each missing concept is
     * created (as a one-time bootstrap authored by {@link TinkarTerm#USER}) with a fully qualified
     * name so it renders meaningfully wherever the narrator's authorship is displayed.
     */
    public static void seedIfAbsent() {
        seedConceptIfAbsent(NARRATOR_AUTHOR, NARRATOR_AUTHOR_NAME);
        seedConceptIfAbsent(NARRATION_MODULE, NARRATION_MODULE_NAME);
    }

    private static void seedConceptIfAbsent(Concept concept, String fullyQualifiedName) {
        if (PrimitiveData.get().hasPublicId(concept.publicId())) {
            return;
        }
        Composer composer = new Composer("komet-narrator-identity-seed");
        Session session = composer.open(State.ACTIVE, TinkarTerm.USER,
                TinkarTerm.DEVELOPMENT_MODULE, TinkarTerm.DEVELOPMENT_PATH);
        session.compose((ConceptAssembler conceptAssembler) -> conceptAssembler
                .concept(concept)
                .attach(FullyQualifiedName.class, name -> name
                        .language(TinkarTerm.ENGLISH_LANGUAGE)
                        .text(fullyQualifiedName)
                        .caseSignificance(TinkarTerm.DESCRIPTION_NOT_CASE_SENSITIVE)));
        composer.commitSession(session);
        LOG.info("Seeded narrator identity concept '{}'", fullyQualifiedName);
    }
}
