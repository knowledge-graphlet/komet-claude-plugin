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
package network.ike.komet.claude.authoring;

import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.PatternFacade;

import java.util.Arrays;
import java.util.List;

/**
 * A dialect a description is given an acceptability in (IKE-Network/ike-issues#812). A dialect is
 * a pattern on the description: its one field is named for the dialect, and its value is the
 * acceptability, preferred or acceptable. A description may be acceptable in one dialect and
 * preferred in another, and each dialect has at most one preferred regular name of a concept.
 */
public enum DescriptionDialect {

    /** United States English, the dialect every description the plugin writes has an acceptability in. */
    US_ENGLISH("US English", KernelPattern.US_DIALECT_PATTERN, KometConcept.US_ENGLISH_DIALECT, "us-dialect"),
    /** Great Britain English. */
    GB_ENGLISH("GB English", KernelPattern.GB_DIALECT_PATTERN, KometConcept.GB_ENGLISH_DIALECT, "gb-dialect");

    private final String label;
    private final PatternFacade pattern;
    private final ConceptFacade dialect;
    private final String key;

    DescriptionDialect(String label, PatternFacade pattern, ConceptFacade dialect, String key) {
        this.label = label;
        this.pattern = pattern;
        this.dialect = dialect;
        this.key = key;
    }

    /** @return the dialect's name as a person chooses it */
    public String label() {
        return label;
    }

    /** @return the pattern an acceptability in the dialect is a semantic of */
    public PatternFacade pattern() {
        return pattern;
    }

    /** @return the dialect's concept, which is the meaning of the pattern's acceptability field */
    public ConceptFacade dialect() {
        return dialect;
    }

    /** @return what an acceptability's identity is derived by, from the identity of its description */
    String key() {
        return key;
    }

    /**
     * Whether the knowledge base has the dialect: its pattern, with the field that carries the
     * acceptability, as a view reads it.
     *
     * @param calc the view
     * @return true when a description can be given an acceptability in the dialect
     */
    public boolean in(ViewCalculator calc) {
        try {
            return PrimitiveData.get().hasPublicId(pattern.publicId())
                    && ConceptProposalWriter.index(ConceptProposalWriter.latest(calc, pattern), dialect) >= 0;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    /**
     * The dialects the knowledge base has, US English first.
     *
     * @param calc the view
     * @return the dialects
     */
    public static List<DescriptionDialect> available(ViewCalculator calc) {
        return Arrays.stream(values()).filter(dialect -> dialect.in(calc)).toList();
    }

    /**
     * The dialect a person chose by name.
     *
     * @param label the name
     * @return the dialect
     * @throws IllegalArgumentException when no dialect is called that
     */
    public static DescriptionDialect labelled(String label) {
        return Arrays.stream(values()).filter(dialect -> dialect.label.equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no dialect is called " + label));
    }
}
