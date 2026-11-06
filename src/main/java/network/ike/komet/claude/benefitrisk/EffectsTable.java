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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Tokens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@code [effects-table]} block as written: two alternatives, and for each criterion what is
 * known of each. The effect is never written; {@link EffectsReading} computes it.
 *
 * @param alternatives the alternatives, the first being the one a positive effect favors
 * @param rows         the evidence by the tree leaf's identifier, in written order
 * @param findings     what could not be read
 */
public record EffectsTable(List<Alternative> alternatives, Map<String, Map<String, Arm>> rows, List<String> findings) {

    /** The block's style. */
    public static final String TAG = "effects-table";

    /**
     * One alternative.
     *
     * @param id   its identifier
     * @param name its concept's name
     */
    public record Alternative(String id, String name) {
    }

    /** What is known of one alternative on one criterion. */
    public sealed interface Arm {
        /** Where the value came from; empty when no source is stated. */
        Optional<String> source();
    }

    /** A sample's mean and spread, the numbers kept as written. */
    public record Measured(String mean, String sd, int n, Optional<String> source) implements Arm {
    }

    /** How many of a sample had the outcome. */
    public record Counted(int events, int of, Optional<String> source) implements Arm {
    }

    /** A range a person gave, with no sample behind it. */
    public record Elicited(String low, String likely, String high, Optional<String> source) implements Arm {
    }

    /** A result stated in words, as a gate takes. */
    public record Found(String result, Optional<String> source) implements Arm {
    }

    /** The findings an arm cites, which an {@link EffectsReading} reads as they stood in a year. */
    public record Cited(List<String> findings, Optional<String> source) implements Arm {
    }

    /**
     * What cited findings say, resolved.
     *
     * @param words  what they say
     * @param result the result in a word, which a gate reads
     * @param source their sources
     */
    public record Reported(String words, Optional<String> result, Optional<String> source) implements Arm {
    }

    /**
     * Reads a block's content.
     *
     * @param body the content
     * @return the table and what could not be read
     */
    public static EffectsTable parse(String body) {
        BlockLines.Parsed lines = BlockLines.parse(body);
        List<String> findings = new ArrayList<>(lines.problems());
        List<Alternative> alternatives = new ArrayList<>();
        Map<String, Map<String, Arm>> rows = new LinkedHashMap<>();
        for (BlockLines.Line line : lines.roots()) {
            Optional<Tokens.Concept> concept = Tokens.concept(line.head());
            if (concept.isEmpty()) {
                findings.add("line " + line.number() + ": no concept: " + line.rest());
            } else if ("alternative".equals(line.word())) {
                alternatives.add(new Alternative(concept.get().identifier().orElse(concept.get().key()), concept.get().label()));
            } else if ("row".equals(line.word())) {
                Map<String, Arm> arms = new LinkedHashMap<>();
                for (BlockLines.Line child : line.children()) {
                    arm(child, findings).ifPresent(arm -> arms.put(child.head(), arm));
                }
                rows.put(concept.get().identifier().orElse(concept.get().key()), arms);
            } else {
                findings.add("line " + line.number() + ": neither an alternative nor a row: " + line.word());
            }
        }
        if (alternatives.size() != 2) {
            findings.add("An effects table compares two alternatives; this one names " + alternatives.size() + ".");
        }
        return new EffectsTable(alternatives, rows, findings);
    }

    private static Optional<Arm> arm(BlockLines.Line line, List<String> findings) {
        Map<String, String> a = line.attributes();
        Optional<String> source = line.attribute("source");
        if (a.containsKey("finding")) {
            return Optional.of(new Cited(List.of(a.get("finding").split(";")), source));
        }
        try {
            if (a.containsKey("mean") && a.containsKey("sd") && a.containsKey("n")) {
                Double.parseDouble(a.get("mean"));
                Double.parseDouble(a.get("sd"));
                return Optional.of(new Measured(a.get("mean"), a.get("sd"), Integer.parseInt(a.get("n")), source));
            }
            if (a.containsKey("count") && a.containsKey("of")) {
                return Optional.of(new Counted(Integer.parseInt(a.get("count")), Integer.parseInt(a.get("of")), source));
            }
        } catch (NumberFormatException notANumber) {
            findings.add("line " + line.number() + ": not a number: " + line.rest());
            return Optional.empty();
        }
        if (a.containsKey("low") && a.containsKey("high") && a.containsKey("likely")) {
            return Optional.of(new Elicited(a.get("low"), a.get("likely"), a.get("high"), source));
        }
        if (a.containsKey("result")) {
            return Optional.of(new Found(a.get("result"), source));
        }
        findings.add("line " + line.number() + ": an arm states mean, sd and n, or count and of, or low, likely and high, or a result, or a finding: " + line.rest());
        return Optional.empty();
    }
}
