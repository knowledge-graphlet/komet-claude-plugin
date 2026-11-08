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
package network.ike.komet.claude.anf;

import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.anf.element.LiftScore;
import network.ike.komet.claude.stamp.Span;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The expected elements of the ANF fixtures, read for scoring a lift with {@link LiftScore}. A
 * fixture's {@code Expected Elements} section gives the narrative the scorer reads, a literal block
 * with a {@code scored-narrative} id, and the table after it: one row per element, naming its
 * statement block, its sentence, and its spans as quote anchors; a row whose statement is
 * {@code not scored} marks words an isolated element may rest on without counting either way.
 */
public final class ScoringFixtures {

    /** The fixtures with expected elements. */
    public static final List<String> FIXTURES = List.of("blood-pressure.adoc", "perrla.adoc");

    private static final Pattern NARRATIVE_ID = Pattern.compile("^\\[#(scored-narrative[\\w-]*)]$");
    private static final Pattern BLOCK_START = Pattern.compile("^\\[anf#([\\w-]+),type=\\w+]$");
    private static final Pattern XREF = Pattern.compile("^<<([\\w-]+)>>$");
    private static final String NOT_SCORED = "not scored";

    private ScoringFixtures() {
    }

    /**
     * One table row.
     *
     * @param statementId the expected statement's block id, empty for words not scored
     * @param sentence    the element sentence
     * @param from        the spans' quote anchors, joined by {@code |}
     */
    public record Row(Optional<String> statementId, String sentence, String from) {
    }

    /**
     * One scored narrative of a fixture.
     *
     * @param fixture the fixture's file name
     * @param id      the literal block's id
     * @param text    the narrative the scorer reads
     * @param rows    its expected elements and words not scored, in the table's order
     */
    public record Narrative(String fixture, String id, String text, List<Row> rows) {
    }

    /** A fixture's text. */
    public static String text(String fixture) {
        try (InputStream in = ScoringFixtures.class.getResourceAsStream("fixtures/" + fixture)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Each {@code [anf#id]} block of a fixture, by its id, in document order. */
    public static Map<String, String> blocks(String text) {
        Map<String, String> blocks = new LinkedHashMap<>();
        String id = null;
        StringBuilder block = new StringBuilder();
        int delimiters = 0;
        for (String line : text.split("\n", -1)) {
            Matcher start = BLOCK_START.matcher(line);
            if (start.matches()) {
                id = start.group(1);
                block.setLength(0);
                delimiters = 0;
            }
            if (id != null) {
                block.append(line).append('\n');
                if (line.equals("--") && ++delimiters == 2) {
                    blocks.put(id, block.toString());
                    id = null;
                }
            }
        }
        return blocks;
    }

    /** Every scored narrative of every fixture. */
    public static List<Narrative> narratives() {
        List<Narrative> all = new ArrayList<>();
        FIXTURES.forEach(fixture -> all.addAll(narratives(fixture, text(fixture))));
        return all;
    }

    /** A fixture's scored narratives. */
    public static List<Narrative> narratives(String fixture, String text) {
        List<Narrative> narratives = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher id = NARRATIVE_ID.matcher(lines[i]);
            if (!id.matches()) {
                continue;
            }
            int open = i + 1;
            if (!lines[open].equals("....")) {
                throw new IllegalStateException(fixture + ": " + id.group(1) + " is not a literal block");
            }
            int close = open + 1;
            while (!lines[close].equals("....")) {
                close++;
            }
            String narrative = String.join("\n", List.of(lines).subList(open + 1, close));
            int table = close + 1;
            while (!lines[table].equals("|===")) {
                table++;
            }
            List<Row> rows = new ArrayList<>();
            for (int r = table + 1; !lines[r].equals("|==="); r++) {
                if (lines[r].startsWith("| ") && !lines[r].startsWith("| Statement ")) {
                    rows.add(row(fixture, lines[r]));
                }
            }
            narratives.add(new Narrative(fixture, id.group(1), narrative, rows));
            i = table;
        }
        return narratives;
    }

    private static Row row(String fixture, String line) {
        String[] cells = line.substring(1).split("(?<!\\\\)\\|");
        if (cells.length != 3) {
            throw new IllegalStateException(fixture + ": a row has three cells: " + line);
        }
        String statement = cells[0].strip();
        Matcher xref = XREF.matcher(statement);
        Optional<String> statementId;
        if (xref.matches()) {
            statementId = Optional.of(xref.group(1));
        } else if (statement.equals(NOT_SCORED)) {
            statementId = Optional.empty();
        } else {
            throw new IllegalStateException(fixture + ": a statement cell is a cross-reference or 'not scored': " + line);
        }
        return new Row(statementId, cells[1].strip(), cells[2].strip().replace("\\|", "|"));
    }

    /** The spans a row's anchors name; every anchor must be found. */
    public static List<Span> spans(Narrative narrative, Row row) {
        List<Span> spans = new ArrayList<>();
        for (String anchor : row.from().split("\\|")) {
            spans.add(Span.resolve(anchor, narrative.text()).orElseThrow(() -> new IllegalStateException(
                    narrative.fixture() + " " + narrative.id() + ": the anchor " + anchor + " is not in the narrative")));
        }
        return spans;
    }

    /** What a narrative expects, its statements parsed from the fixture's blocks through {@code resolver}. */
    public static LiftScore.Expectation expectation(Narrative narrative, AnfAdoc.ConceptResolver resolver) {
        Map<String, String> blocks = blocks(text(narrative.fixture()));
        List<LiftScore.Expected> elements = new ArrayList<>();
        List<Span> notScored = new ArrayList<>();
        for (Row row : narrative.rows()) {
            List<Span> spans = spans(narrative, row);
            if (row.statementId().isEmpty()) {
                notScored.addAll(spans);
                continue;
            }
            String id = row.statementId().get();
            String block = blocks.get(id);
            if (block == null) {
                throw new IllegalStateException(narrative.fixture() + ": no statement block " + id);
            }
            elements.add(new LiftScore.Expected(id, row.sentence(), spans,
                    Optional.of(AnfAdoc.parse(block, resolver))));
        }
        return new LiftScore.Expectation(narrative.text(), elements, notScored);
    }

    /** The lift a fixture expects, as an element set: every expected element, lifted to its expected statement. */
    public static ElementSet perfectLift(LiftScore.Expectation expectation) {
        List<ClinicalElement> elements = new ArrayList<>();
        for (LiftScore.Expected expected : expectation.elements()) {
            ClinicalElement element = ClinicalElement.proposed(expected.id(), expected.sentence(), expected.spans());
            elements.add(expected.statement().map(element::lifted).orElse(element));
        }
        return new ElementSet(expectation.narrative(), elements, List.of());
    }
}
