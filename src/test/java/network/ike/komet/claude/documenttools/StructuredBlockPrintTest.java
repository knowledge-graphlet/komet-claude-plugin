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
package network.ike.komet.claude.documenttools;

import network.ike.komet.document.print.DocumentModel;
import network.ike.komet.document.print.StoredDocumentModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A structured block the assistant's kinds read prints as one block of its source: the document's
 * print model over the complex-concept kind, which komet-claude-plugin provides.
 */
class StructuredBlockPrintTest {

    private static final String HEADER = """
            :topic-id: t-fixture
            = The Fixture Title

            """;

    @Test
    void aStructuredOpenBlockIsOneBlockOfSource() {
        var blocks = StoredDocumentModel.of(StoredDocuments.parse(HEADER + """
                [complex-concept]
                --
                concept: k:A[a]
                  focus: k:B[b]
                  requisite: k:C[c]
                --
                """)).blocks();
        assertEquals(1, blocks.size(), blocks.toString());
        var source = assertInstanceOf(DocumentModel.Verbatim.class, blocks.getFirst());
        assertEquals("concept: k:A[a]\n  focus: k:B[b]\n  requisite: k:C[c]", source.text());
    }
}
