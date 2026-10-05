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
package network.ike.komet.claude.ui;

import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.komet.markdown.richtext.InlinePiece;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.koncept.ComponentText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nid found in text is never resolved against the open store
 * ({@code IKE-Network/ike-issues#1170}). Text is kept, and read later against a store in which
 * the same number identifies another component or none; the transcript must not draw a badge
 * for whatever that number happens to be here.
 *
 * <p>The store-free grammar tests cannot show this: without a store nothing resolves, so every
 * token is literal. Here the store is live and holds the very component whose nid is written
 * in the text — the case in which the nid forms used to get a badge — and each test also
 * resolves the same component by its UUID, so a pass cannot come from a store that resolves
 * nothing. Node suppliers are never invoked: building a badge needs a JavaFX toolkit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NidInTextIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewCalculator view;
    /** A component the store holds, in the three forms text can name it. */
    private int nid;
    private String uuid;
    private String uuidToken;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(),
                "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath()
                        + " (copied by maven-dependency-plugin in process-test-resources).");
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
        view = Calculators.View.Default();
        nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        uuid = ComponentText.identifier(nid);
        uuidToken = ComponentText.badge(view, nid).orElseThrow();
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void aUuidTokenGetsABadgeAndTheNidFormsBesideItStayText() {
        ConceptChipInlineDecorator decorator = new ConceptChipInlineDecorator(view, 13);
        String text = "First " + uuidToken + ", then k:nid=" + nid + "[English language], then nid=" + nid + ".";

        List<InlinePiece> pieces = decorator.decorate(text, StyleAttributeMap.EMPTY);

        List<InlinePiece.NodeRun> badges = pieces.stream()
                .filter(InlinePiece.NodeRun.class::isInstance).map(InlinePiece.NodeRun.class::cast).toList();
        assertEquals(1, badges.size(), "only the UUID token is a badge; neither nid form is");
        assertEquals(uuidToken, badges.get(0).plainText(), "the badge stands for the UUID token");

        StringBuilder plain = new StringBuilder();
        for (InlinePiece piece : pieces) {
            plain.append(piece.plainText());
        }
        assertEquals(text, plain.toString(), "the nid forms are still there, as the text they were");
    }

    @Test
    void aBareUuidGetsABadgeAndABareNidDoesNot() {
        ConceptChipInlineDecorator decorator = new ConceptChipInlineDecorator(view, 13);

        long badgesForUuid = decorator.decorate("see " + uuid + " here", StyleAttributeMap.EMPTY).stream()
                .filter(InlinePiece.NodeRun.class::isInstance).count();
        assertEquals(1, badgesForUuid, "the store holds the component, so its UUID gets a badge");

        long badgesForNid = decorator.decorate("see nid=" + nid + " here", StyleAttributeMap.EMPTY).stream()
                .filter(InlinePiece.NodeRun.class::isInstance).count();
        assertEquals(0, badgesForNid, "the store holds a component with this nid, and it gets no badge");
    }

    @Test
    void aTreeLineWithANidIsNotResolvedAndOneWithTheUuidIs() {
        PublicId byUuid = KonceptTreeBlockRenderer.resolvePid("uuid", uuid);
        assertNotNull(byUuid, "the store holds the component, so its UUID resolves");
        assertTrue(PublicId.equals(PrimitiveData.publicId(nid), byUuid));

        assertNull(KonceptTreeBlockRenderer.resolvePid("nid", Integer.toString(nid)),
                "the store holds a component with this nid, and the tree line does not resolve to it");
    }

    @Test
    void whatTheComposeSurfaceWritesIsWhatTheTranscriptReads() {
        // The token the compose surface and a dropped component write (ComponentText.badge) is
        // one whole token of the transcript's grammar, and it resolves to the same component.
        Matcher m = ConceptChipInlineDecorator.TOKEN.matcher(uuidToken);
        assertTrue(m.find() && m.start() == 0 && m.end() == uuidToken.length());
        assertEquals("uuid", m.group("kind"));
        assertEquals(uuid, m.group("kid"));
        assertEquals(view.getDescriptionText(nid).orElseThrow(), m.group("klabel"));
        assertEquals(nid, PrimitiveData.nid(PublicIds.of(m.group("kid"))));
    }
}
