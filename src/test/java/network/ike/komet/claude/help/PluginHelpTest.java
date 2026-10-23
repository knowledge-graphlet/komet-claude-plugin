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

import dev.ikm.komet.layout.help.KlHelpProvider;
import dev.ikm.komet.markdown.richtext.InlineDecorator;
import dev.ikm.komet.markdown.richtext.MarkdownRichTextRenderer;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's own help book, read from the jar's resources as Komet reads it: every topic in
 * its contents exists, renders, and links only to topics of the book; and the card's topic is
 * among them.
 */
class PluginHelpTest {

    private static final Pattern XREF_LINK = Pattern.compile("\\]\\(xref:([^)]+)\\)");

    private final PluginHelp book = new PluginHelp();

    @Test
    void everyTopicInTheContentsExistsAndHasATitle() {
        List<KlHelpProvider.Entry> contents = book.contents();
        assertFalse(contents.isEmpty(), "the book has contents");
        for (KlHelpProvider.Entry entry : contents) {
            assertTrue(book.source(entry.topicId()).isPresent(), "topic " + entry.topicId() + " exists");
            assertFalse(entry.title().equals(entry.topicId()), "topic " + entry.topicId() + " has a title");
        }
        Set<String> ids = topicIds();
        assertTrue(ids.contains(PluginHelp.STAMPED_DOCUMENT.topicId()), "the STAMPed Document card's topic is in the contents");
    }

    @Test
    void everyCrossReferenceNamesATopicOfTheBook() {
        Set<String> ids = topicIds();
        for (String id : ids) {
            Matcher link = XREF_LINK.matcher(AsciiDocMarkdown.markdown(book.source(id).orElseThrow()));
            while (link.find()) {
                String target = AsciiDocMarkdown.topicOf(link.group(1));
                assertTrue(ids.contains(target), id + " links to " + target + ", which is not a topic of the book");
            }
        }
    }

    @Test
    void everyTopicRendersAsText() {
        for (String id : topicIds()) {
            List<RichParagraph> paragraphs = new ArrayList<>();
            List<String> plain = new ArrayList<>();
            new MarkdownRichTextRenderer(13, InlineDecorator.PLAIN)
                    .render(AsciiDocMarkdown.markdown(book.source(id).orElseThrow()), StyleAttributeMap.EMPTY, paragraphs, plain);
            assertEquals(paragraphs.size(), plain.size());
            String text = String.join("\n", plain);
            assertTrue(text.length() > 200, id + " renders to text");
            assertFalse(text.contains("xref:"), id + " shows no raw cross reference");
            assertFalse(text.contains("::"), id + " shows no raw description-list markup");
        }
    }

    @Test
    void theOpeningWalkthroughExplainsTheKindsOfData() {
        String opening = AsciiDocMarkdown.markdown(book.source("opening-a-document").orElseThrow());
        assertTrue(opening.contains("Open file"), opening);
        String kinds = AsciiDocMarkdown.markdown(book.source("kinds-of-data").orElseThrow());
        for (String kind : List.of("## Knowledge", "## Instance", "## Ephemeral")) {
            assertTrue(kinds.contains(kind), kind);
        }
    }

    private Set<String> topicIds() {
        return book.contents().stream().map(KlHelpProvider.Entry::topicId).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
