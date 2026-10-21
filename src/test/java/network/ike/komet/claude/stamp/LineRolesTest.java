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
package network.ike.komet.claude.stamp;

import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What each line of a document is, as a semantic vertex: its meaning and the parts its markup carried. */
class LineRolesTest {

    private static final String TEXT = """
            // dev-example
            :topic-id: dev-example

            [[dev-example]]
            = The Title

            == One

            First claim.
            .A listing
            [source,yaml]
            ----
            key: value
            ----

            * an item
            ** a nested item

            A term:: its text

            include::child.adoc[leveloffset=+1]
            ifndef::backend-pdf[]
            """;

    @Test
    void everyLineHasItsRoleAndItsSource() {
        List<LineRoles.Role> roles = LineRoles.of(IkeDialectParser.parse(TEXT));
        List<ConceptFacade> expected = List.of(
                IkeConcept.COMMENT_LINE, IkeConcept.ATTRIBUTE_ENTRY_LINE, IkeConcept.BLANK_LINE,
                IkeConcept.ANCHOR_LINE, IkeConcept.HEADING_LINE, IkeConcept.BLANK_LINE,
                IkeConcept.HEADING_LINE, IkeConcept.BLANK_LINE,
                IkeConcept.PROSE_LINE, IkeConcept.BLOCK_TITLE_LINE, IkeConcept.BLOCK_ATTRIBUTE_LINE,
                IkeConcept.DELIMITER_LINE, IkeConcept.VERBATIM_LINE, IkeConcept.DELIMITER_LINE, IkeConcept.BLANK_LINE,
                IkeConcept.ITEM_LINE, IkeConcept.ITEM_LINE, IkeConcept.BLANK_LINE, IkeConcept.ITEM_LINE, IkeConcept.BLANK_LINE,
                IkeConcept.ASCIIDOC_INCLUDE, IkeConcept.ASCIIDOC_CONDITIONAL);
        assertEquals(expected.size(), roles.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).publicId(), roles.get(i).meaning().publicId(), "line " + (i + 1) + ": " + roles.get(i));
        }
        assertEquals("== One", prop(roles, 7, IkeConcept.LINE_SOURCE), "every line keeps its source");
    }

    @Test
    void theMarkupsPartsAreProperties() {
        List<LineRoles.Role> roles = LineRoles.of(IkeDialectParser.parse(TEXT));
        assertEquals("topic-id", prop(roles, 2, IkeConcept.ASCIIDOC_ATTRIBUTE_NAME));
        assertEquals("dev-example", prop(roles, 2, IkeConcept.ATTRIBUTE_VALUE));
        assertEquals("dev-example", prop(roles, 4, IkeConcept.ANCHOR_ID));
        assertEquals(0, prop(roles, 5, IkeConcept.DOCUMENT_ELEMENT_LEVEL), "the document title is level 0");
        assertEquals("The Title", prop(roles, 5, IkeConcept.DOCUMENT_ELEMENT_TITLE));
        assertEquals(1, prop(roles, 7, IkeConcept.DOCUMENT_ELEMENT_LEVEL));
        assertEquals("One", prop(roles, 7, IkeConcept.DOCUMENT_ELEMENT_TITLE));
        assertEquals("A listing", prop(roles, 10, IkeConcept.DOCUMENT_ELEMENT_TITLE));
        assertEquals("[source,yaml]", prop(roles, 11, IkeConcept.DOCUMENT_ELEMENT_PARAMETERS));
        assertEquals("*", prop(roles, 16, IkeConcept.LIST_MARKER));
        assertEquals(1, prop(roles, 16, IkeConcept.LIST_DEPTH));
        assertEquals(2, prop(roles, 17, IkeConcept.LIST_DEPTH));
        assertEquals("A term", prop(roles, 19, IkeConcept.ASCIIDOC_TERM_TEXT));
        assertEquals("child.adoc", prop(roles, 21, IkeConcept.ASCIIDOC_REFERENCE_TARGET));
        assertEquals("backend-pdf", prop(roles, 22, IkeConcept.ASCIIDOC_ATTRIBUTE_NAME));
        assertEquals(true, prop(roles, 22, IkeConcept.ASCIIDOC_NEGATED));
    }

    private static Object prop(List<LineRoles.Role> roles, int line, ConceptFacade key) {
        for (Map.Entry<ConceptFacade, Object> e : roles.get(line - 1).properties().entrySet()) {
            if (e.getKey().publicId().equals(key.publicId())) {
                return e.getValue();
            }
        }
        return null;
    }
}
