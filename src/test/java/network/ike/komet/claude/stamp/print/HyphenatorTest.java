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
package network.ike.komet.claude.stamp.print;

import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hyphenator against Typst's own hyphenation: hypher 0.1.5's unit tests, syllables read
 * back from typst 0.15.1 (every character labelled on a one-point-wide page, so each syllable
 * is a line of its own), and the corpus goldens, where every hyphenated line records the
 * offset Typst broke the word at.
 */
class HyphenatorTest {

    private static final Hyphenator EN = Hyphenator.english();

    @Test
    void readsEveryPatternOfTheVendoredFile() {
        assertEquals(4938, EN.patterns(), "the \\patterns block of hyph-en-us.tex (2005-05-30)");
    }

    @Test
    void hyphenatesAsHyphersOwnTestsDo() {
        // hypher 0.1.5, src/lib.rs test_english: the word with its hyphens, case kept.
        for (String expected : List.of("", "hi", "wel-come", "walk-ing", "cap-tiVe", "pur-sue", "wHaT-eVeR", "bro-ken", "ex-ten-sive",
                "Prob-a-bil-ity", "rec-og-nize")) {
            assertEquals(expected, EN.show(expected.replace("-", "")));
        }
    }

    @Test
    void hyphenatesAsTypstDoes() {
        // typst 0.15.1 (hypher 0.1.5), lang "en": syllables read back through per-character labels, 2026-10-20.
        for (String expected : List.of("hy-phen-ation", "ma-te-ri-al-iza-tion", "con-fig-u-ra-tion", "syn-chro-nized", "doc-u-ment",
                "work-ing", "con-cate-na-tion", "as-so-ci-ates", "pro-ject", "pre-sent", "table", "uni-ver-sity", "Sync-thing", "In-tel-liJ",
                "repos-i-tory", "com-mit-ted", "ma-chines", "demon-stra-tion", "in-con-sis-ten-cies", "dec-li-na-tion", "oblig-a-tory",
                "phil-an-thropic", "rec-i-proc-ity", "rec-og-nizance", "re-for-ma-tion", "ret-ri-bu-tion", "un-com-mit-ted",
                "Au-tho-riza-tion", "Knowl-edge", "si-mul-ta-ne-ously", "dis-am-bigua-tion", "Thun-der-bolt", "bidi-rec-tional",
                "WORK-ING", "ScHeD-uLe", "straight-for-ward-ness", "hello", "ab", "abc", "abcd", "abcde", "jumps", "eigh-teen", "lemma")) {
            assertEquals(expected, EN.show(expected.replace("-", "")));
        }
    }

    @Test
    void ignoresTheExceptionsAsHypherDoes() {
        // hyph-en-us.tex lists ta-ble, as-so-ciates and pro-ject under \hyphenation; hypher reads \patterns only.
        assertEquals("table", EN.show("table"));
        assertEquals("as-so-ci-ates", EN.show("associates"));
        assertEquals("pro-ject", EN.show("project"));
    }

    @Test
    void keepsTwoCharactersBeforeAndThreeAfterAHyphen() {
        assertArrayEquals(new int[] {2, 5}, EN.points("extensive"), "ex|ten|sive");
        assertArrayEquals(new int[] {}, EN.points("abcd"), "shorter than the bounds allow");
        assertEquals("a-bcde", Hyphenator.of("\\patterns{a1b}", 1, 1).show("abcde"), "other bounds");
        assertEquals("abcde", Hyphenator.of("\\patterns{a1b}", 2, 3).show("abcde"));
        assertEquals("ab-cde", Hyphenator.of("\\patterns{b1c} % c1d\n", 2, 3).show("abcde"), "a comment inside the block");
    }

    @Test
    void pointsInASpanFollowTypstsWordRules() {
        String text = "(materialization, don't e.g. JDK27 self-contained working_set https://github.com/extensive www.extensive.org *extensive*";
        int[] points = EN.pointsIn(text, 0, text.indexOf(','));
        assertArrayEquals(new int[] {3, 5, 7, 9, 12}, points, "ma|te|ri|al|iza|tion after the parenthesis");
        assertArrayEquals(new int[] {}, EN.pointsIn(text, text.indexOf("don't"), text.indexOf("don't") + 5), "an apostrophe makes a word of more than letters");
        assertArrayEquals(new int[] {}, EN.pointsIn(text, text.indexOf("e.g."), text.indexOf("e.g.") + 4));
        assertArrayEquals(new int[] {}, EN.pointsIn(text, text.indexOf("JDK27"), text.indexOf("JDK27") + 5), "digits join the letters");
        int self = text.indexOf("self-contained");
        assertArrayEquals(new int[] {self + 8}, EN.pointsIn(text, self, self + 14), "con|tained: the word after its own hyphen on its own");
        int ws = text.indexOf("working_set");
        assertArrayEquals(new int[] {}, EN.pointsIn(text, ws, ws + 11), "an underscore joins the letters");
        int url = text.indexOf("https://");
        assertArrayEquals(new int[] {}, EN.pointsIn(text, url, text.indexOf(' ', url)), "a URL");
        int www = text.indexOf("www.");
        assertArrayEquals(new int[] {}, EN.pointsIn(text, www, text.indexOf(' ', www)), "a bare domain");
        int star = text.indexOf("*extensive*");
        assertArrayEquals(new int[] {star + 3, star + 6}, EN.pointsIn(text, star, star + 11), "markup around a word is not the word");
    }

    @Test
    void theCorpusGoldensBreakWordsAtThePatternsPoints() throws Exception {
        // Every hyphenated golden line ends at an offset the Typst oracle chose by the tail's width; the patterns
        // must name that offset (or the word carried a hyphen of its own there) for all but the few the width named
        // loosely — the inline raw spans and text the alignment maps loosely, which the goldens' notes count.
        Path corpus = Path.of("src/test/resources/fidelity/corpus");
        Path goldens = Path.of("src/test/resources/fidelity/golden");
        List<String> names;
        try (Stream<Path> files = Files.list(corpus)) {
            names = files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".adoc")).map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        }
        int hyphenated = 0;
        int atPoint = 0;
        List<String> misses = new ArrayList<>();
        for (String name : names) {
            Path file = goldens.resolve(name + ".layout");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<Unit> units = ModelUnits.of(StoredDocumentModel.of(StoredFixture.parse(Files.readString(corpus.resolve(name + ".adoc"), StandardCharsets.UTF_8))), 12);
            for (Line line : GoldenLayout.read(file).layout().lines()) {
                if (!line.hyphenated()) {
                    continue;
                }
                hyphenated++;
                String text = units.get(line.unit()).text();
                int cut = line.to();
                int ws = cut;
                while (ws > 0 && !Character.isWhitespace(text.charAt(ws - 1))) {
                    ws--;
                }
                int we = cut;
                while (we < text.length() && !Character.isWhitespace(text.charAt(we))) {
                    we++;
                }
                int wordStart = ws;
                boolean own = Arrays.stream(TypstOracle.ownCuts(text.substring(ws, we))).anyMatch(c -> wordStart + c == cut);
                if (own || Arrays.stream(EN.pointsIn(text, ws, we)).anyMatch(p -> p == cut)) {
                    atPoint++;
                } else if (misses.size() < 40) {
                    misses.add(name + " " + line.unit() + ": '" + text.substring(ws, cut) + "|" + text.substring(cut, we) + "' points "
                            + EN.show(text.substring(ws, we)));
                }
            }
        }
        assertTrue(hyphenated > 400, "the goldens hold " + hyphenated + " hyphenated lines");
        double share = 100.0 * atPoint / hyphenated;
        System.out.printf("%.1f%% of %d hyphenated golden lines break at a hyphenation point of the word or a hyphen of its own%n", share, hyphenated);
        assertTrue(share >= 98, String.format("%.1f%% of %d hyphenated golden lines break at a hyphenation point; misses: %s", share, hyphenated, misses));
    }
}
