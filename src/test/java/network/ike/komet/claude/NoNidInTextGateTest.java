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
package network.ike.komet.claude;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A gate on the plugin's own sources: none of them makes a call that writes a nid into text
 * ({@code IKE-Network/ike-issues#1170}).
 *
 * <p>The assistant's text is kept — conversation files, the conversation journal, commit
 * comments, Zulip messages, exported transcripts — and a nid means nothing outside the store
 * that assigned it. The calls below are the ones that put a nid into text: each answers with
 * the nid when a name does not resolve, or always. They are a method call away on every view
 * calculator, the compiler has no objection to them, and on a store whose components all have
 * descriptions they never show the nid, so no test of behavior catches one being added. This
 * test reads the sources instead.
 *
 * <p>When it fails, use the replacement it names. {@code ComponentText} and
 * {@code DefinitionText} hold every text form of a component and of a definition tree.
 */
class NoNidInTextGateTest {

    /** The plugin's main sources, relative to the module directory the tests run in. */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** A call that writes a nid into text, and what to use in its place. */
    private record Forbidden(String what, Pattern call, String instead) {
    }

    private static final List<Forbidden> FORBIDDEN = List.of(
            new Forbidden("a calculator method that answers with the nid when no description resolves",
                    Pattern.compile("\\w*OrNid\\s*\\("),
                    "ComponentText.name, preferredName or fullyQualifiedName"),
            new Forbidden("the store's default text, which is <nid> when there is no description",
                    Pattern.compile("PrimitiveData\\s*\\.\\s*text(?:Fast|WithNid)?\\s*\\("),
                    "ComponentText.name"),
            new Forbidden("the calculator's semantic text, which names its parts with the OrNid methods",
                    Pattern.compile("\\.\\s*getSemanticText\\s*\\("),
                    "ComponentText.preferredName"),
            new Forbidden("a nid appended to a label",
                    Pattern.compile("\"[^\"\\n]*\\bnid\\s*[=:]?\\s*\"\\s*\\+"),
                    "ComponentText.identifier"));

    /** Block comments and javadoc, which may name the forbidden calls in prose. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** A line comment, from {@code //} to the end of the line, unless the slashes follow a colon (a URL). */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?<!:)//[^\\n]*");

    @Test
    void noMainSourceCallsWhatWritesANidIntoText() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES),
                "The gate reads " + MAIN_SOURCES.toAbsolutePath() + "; run the tests from the module directory.");

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                scanned++;
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                for (Forbidden forbidden : FORBIDDEN) {
                    Matcher matcher = forbidden.call().matcher(code);
                    while (matcher.find()) {
                        violations.add(MAIN_SOURCES.relativize(file) + ":" + lineOf(code, matcher.start())
                                + "  " + matcher.group().strip() + "  — " + forbidden.what()
                                + "; use " + forbidden.instead());
                    }
                }
            }
        }
        assertTrue(scanned > 50, "The gate found only " + scanned + " sources; it is not reading the plugin.");
        assertEquals(List.of(), violations, "Calls that write a nid into text");
    }

    @Test
    void theGateRecognizesEachForbiddenCall() {
        // The gate is only as good as its patterns: each must match the call it stands for, and
        // none may match the replacement or a comment that names the call.
        assertEquals(1, hits("String s = view.getDescriptionTextOrNid(nid);"));
        assertEquals(1, hits("return v.getPreferredDescriptionTextWithFallbackOrNid(nid);"));
        assertEquals(1, hits("sb.append(PrimitiveData.text(nid));"));
        assertEquals(1, hits("sb.append(PrimitiveData.textWithNid(nid));"));
        assertEquals(1, hits("Optional<String> t = view.getSemanticText(nid);"));
        assertEquals(1, hits("return \"nid=\" + nid;"));
        assertEquals(1, hits("return \"[nid \" + nid + \"]\";"));
        assertEquals(1, hits("message = \"Concept under review: \" + name + \"  (nid \" + nid + \").\";"));

        assertEquals(0, hits("String s = ComponentText.name(view, nid);"));
        assertEquals(0, hits("LOG.info(\"discovered nid={} id={}\", nid, id);"));
        assertEquals(0, hits("// the calculator's getDescriptionTextOrNid(nid) answers with the nid"));
        assertEquals(0, hits("/** Replaces {@code view.getSemanticText(nid)}. */ int x;"));
        assertEquals(0, hits("case \"nid\" -> null;"));
    }

    /** How many forbidden calls the gate finds in a piece of source. */
    private static int hits(String source) {
        String code = withoutComments(source);
        int hits = 0;
        for (Forbidden forbidden : FORBIDDEN) {
            Matcher matcher = forbidden.call().matcher(code);
            while (matcher.find()) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * The source with its comments blanked. Line breaks are kept, so a match still reports the
     * line it is on.
     */
    private static String withoutComments(String source) {
        String withoutBlocks = BLOCK_COMMENT.matcher(source)
                .replaceAll(match -> match.group().replaceAll("[^\\n]", " "));
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll("");
    }

    /** The one-based line of an offset. */
    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
