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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A person's turn is plain text unless it carries a fenced block, which is deliberate. */
class MarkdownRichTextEntryTest {

    @Test
    @DisplayName("Typed prose stays plain, whatever Markdown-looking marks it carries")
    void proseStaysPlain() {
        MarkdownRichText.Entry entry = MarkdownRichText.Entry.user("What is *this* # about? ``` inline ticks are not a fence");
        assertEquals(MarkdownRichText.Role.USER, entry.role());
        assertFalse(entry.markdown());
        assertFalse(MarkdownRichText.Entry.user(null).markdown());
    }

    @Test
    @DisplayName("A pasted fenced block renders in the prompt as it would in a reply")
    void fencedBlockRenders() {
        String text = """
                Here is the tree:
                ```decision-tree health-price=100000
                decision: k:ScanNow[Scan now?] @id=rda-scan-now
                  alternative: k:Scan[scan] @id=rda-scan
                ```
                """;
        assertTrue(MarkdownRichText.Entry.user(text).markdown());
        assertTrue(MarkdownRichText.Entry.fenced("```koncept-tree\nk:sctid=1[x]\n```"));
    }
}
