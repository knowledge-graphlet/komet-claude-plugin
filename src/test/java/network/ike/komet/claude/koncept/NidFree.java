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
package network.ike.komet.claude.koncept;

import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.id.IntIds;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.language.LanguageCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the store-backed tests of {@code IKE-Network/ike-issues#1170} share: the assertion that a
 * text holds no nid, and a view under which no component has a description.
 *
 * <p>The tests run against the IKE starter set in the ephemeral store. That store numbers
 * components upward from {@code Integer.MIN_VALUE + 1}, so in decimal every nid it assigns is a
 * minus sign and ten digits beginning {@code 21474} or {@code 21473}. {@link #assertNoNid} looks
 * for a number of that shape, and for the forms a nid has been written in:
 * {@code <nid>}, {@code nid=N}, {@code [nid N]}, {@code (nid N)} and {@code nid: N}.
 */
public final class NidFree {

    /** A nid of the ephemeral store in decimal: {@code Integer.MIN_VALUE} plus a small count. */
    private static final Pattern EPHEMERAL_NID = Pattern.compile("-2147[34]\\d{5}(?!\\d)");

    /** The form {@code PrimitiveData.text} writes for a component with no description. */
    private static final Pattern ANGLE_BRACKET_NID = Pattern.compile("<-?\\d+>");

    /** A number labelled as a nid: {@code nid=N}, {@code nid N}, {@code nid: N}. */
    private static final Pattern LABELLED_NID = Pattern.compile("(?i)\\bnid\\b\\s*[=:]?\\s*-?\\d+");

    private NidFree() {
    }

    /**
     * Fails when the text holds a nid.
     *
     * @param what a few words naming the text, for the failure message
     * @param text the text to examine
     */
    public static void assertNoNid(String what, String text) {
        for (Pattern form : new Pattern[]{EPHEMERAL_NID, ANGLE_BRACKET_NID, LABELLED_NID}) {
            Matcher matcher = form.matcher(text);
            if (matcher.find()) {
                fail(what + " holds a nid: \"" + matcher.group() + "\" in:\n" + text);
            }
        }
    }

    /**
     * Fails when the text holds the given nid in decimal.
     *
     * @param what a few words naming the text, for the failure message
     * @param text the text to examine
     * @param nid  the nid that must not appear
     */
    public static void assertNoNid(String what, String text, int nid) {
        assertNoNid(what, text);
        if (text.contains(Integer.toString(nid))) {
            fail(what + " holds the nid " + nid + " in:\n" + text);
        }
    }

    /**
     * A view under which no component of the starter data has a description. It is the default
     * view in every respect but one: its language coordinate looks for descriptions in the
     * comment pattern, which holds none. Every name therefore takes its fallback. Before
     * {@code IKE-Network/ike-issues#1170} that fallback was the nid.
     *
     * <p>A coordinate for a language the starter data is not written in would not do: the
     * calculator's fully qualified name is looked up by description type alone, in whatever
     * language, so under a Spanish coordinate it still resolves.
     *
     * @return a calculator for a view of the development path that resolves no description
     */
    public static ViewCalculator viewWithNoDescriptions() {
        LanguageCoordinateRecord noDescriptions = LanguageCoordinateRecord.make(
                KernelTerm.ENGLISH_LANGUAGE.nid(),
                IntIds.list.of(KernelTerm.COMMENT_PATTERN.nid()),
                IntIds.list.of(KernelTerm.REGULAR_NAME_DESCRIPTION_TYPE.nid(),
                        KernelTerm.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.nid()),
                IntIds.list.empty(),
                IntIds.list.empty());
        ViewCoordinateRecord coordinate = ViewCoordinateRecord.make(
                Coordinates.Stamp.DevelopmentLatest(),
                noDescriptions,
                Coordinates.Logic.ElPlusPlus(),
                Coordinates.Navigation.inferred(),
                Coordinates.Edit.Default());
        return ViewCalculatorWithCache.getCalculator(coordinate);
    }
}
