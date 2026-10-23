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
package network.ike.komet.claude.help;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The projection of help topics, IKE-dialect AsciiDoc, to the Markdown the rich text renderer draws. */
class AsciiDocMarkdownTest {

    @Test
    void titleAndSectionsBecomeHeadings() {
        String md = AsciiDocMarkdown.markdown("= Title\n:toc:\n\nIntro.\n\n== Part\n\nBody.\n\n=== Deeper\n\nMore.\n");
        assertEquals("# Title\n\nIntro.\n\n## Part\n\nBody.\n\n### Deeper\n\nMore.\n", md);
    }

    @Test
    void paragraphLinesJoinAndInlineMarkupCarriesOver() {
        String md = AsciiDocMarkdown.markdown("A *strong* and _emphasised_\nword with `code` in it.\n");
        assertEquals("A **strong** and *emphasised* word with `code` in it.\n", md);
    }

    @Test
    void crossReferencesBecomeXrefLinksAndUrlsKeepTheirAddress() {
        String md = AsciiDocMarkdown.markdown("See xref:kinds-of-data.adoc[Kinds of data], <<stores>> and https://ike.network[the site].\n");
        assertEquals("See [Kinds of data](xref:kinds-of-data.adoc), [stores](xref:stores) and [the site](https://ike.network).\n", md);
        assertEquals("kinds-of-data", AsciiDocMarkdown.topicOf("kinds-of-data.adoc#instance"));
        assertEquals("stores", AsciiDocMarkdown.topicOf("stores"));
    }

    @Test
    void orderedListsNumberTheirStepsAndNestedListsIndent() {
        String md = AsciiDocMarkdown.markdown(". Open the menu.\n. Choose a file\nthat is committed.\n** It may include others.\n. Wait.\n");
        assertEquals("1. Open the menu.\n2. Choose a file that is committed.\n   - It may include others.\n3. Wait.\n", md);
    }

    @Test
    void descriptionListsBecomeBoldTerms() {
        String md = AsciiDocMarkdown.markdown("Layer:: What is drawn over the text.\nMore:: The verbs.\n");
        assertEquals("- **Layer** — What is drawn over the text.\n- **More** — The verbs.\n", md);
    }

    @Test
    void admonitionsAreQuotedWithTheirLabel() {
        assertEquals("> **Note:** Mind the gap.\n", AsciiDocMarkdown.markdown("NOTE: Mind the gap.\n"));
        assertEquals("> **Tip:** One.\n>\n> Two.\n", AsciiDocMarkdown.markdown("[TIP]\n====\nOne.\n\nTwo.\n====\n"));
    }

    @Test
    void listingsBecomeFencedCodeWithoutTheirDelimiters() {
        String md = AsciiDocMarkdown.markdown(".Example\n[source,bash]\n----\necho *hi*\n----\n");
        assertEquals("**Example**\n\n```\necho *hi*\n```\n", md);
    }

    @Test
    void markdownPunctuationInTextIsEscaped() {
        String md = AsciiDocMarkdown.markdown("Choose [then] a_b < c.\n");
        assertTrue(md.contains("\\[then\\]"), md);
        assertTrue(md.contains("a\\_b \\< c"), md);
    }

    @Test
    void tablesBecomeGfmTables() {
        String md = AsciiDocMarkdown.markdown("|===\n| Kind | Record\n\n| Ephemeral | git\n|===\n");
        assertEquals("| Kind | Record |\n| --- | --- |\n| Ephemeral | git |\n", md);
    }
}
