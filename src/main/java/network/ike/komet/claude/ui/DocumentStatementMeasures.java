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

import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anf.Circumstance;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.StatementMeasures;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a document's ANF statement states, as a graphlet model's parameter reads it
 * ({@code dev-decision-point-knowledge}, settled 2026-11-06): the statement's result is the range
 * someone determined, and its measure semantic the scale. A share's semantic is a share of the
 * subject group, members with the topic over members of the group on zero to one: the semantic
 * names the kind of frame, a group, and the statement's subject of information supplies the
 * particular group, never the semantic. The tree reads such a share as the share at the node
 * that carries it, which is where the condition is checked. The share's semantic is read by its
 * identity when the store holds the term, and by its name, share, fraction, proportion,
 * probability or percent, on a store that predates it or in a candidate. Any other scale, a
 * currency say, is the statement's own, by its concept in the store or by its candidate's label,
 * one identity per label local to the document. The parameter is named by the statement's topic
 * and, when it names one, the group it is about.
 */
final class DocumentStatementMeasures {

    private DocumentStatementMeasures() {
    }

    /** The measure a statement states, when it is a performance with a bounded result. */
    static Optional<StatementMeasures.Stated> of(AnfStatement statement, Identities ids, DecisionTerms terms) {
        if (!(statement.circumstance() instanceof Circumstance.Performance performance)) {
            return Optional.empty();
        }
        AnfStatement.Result result = performance.result();
        if (result == null) {
            return Optional.empty();
        }
        Bounds range = new Bounds(
                result.lowerBound() == null ? Double.NEGATIVE_INFINITY : result.lowerBound(),
                result.upperBound() == null ? Double.POSITIVE_INFINITY : result.upperBound(),
                !result.includeLowerBound(), !result.includeUpperBound());
        long semantic = scale(result.measureSemantic(), ids, terms);
        return Optional.of(new StatementMeasures.Stated(range, semantic, name(statement, semantic, ids, terms)));
    }

    /** The scale a result is on, as the tree reads it: a share is the share at a node; anything else is its own concept. */
    private static long scale(AnfValue semantic, Identities ids, DecisionTerms terms) {
        return switch (semantic) {
            case AnfValue.Grounded g -> isShare(g.nid(), terms) || isFraction(g.label()) || isFraction(ids.label(g.nid()))
                    ? terms.shareAtNode() : g.nid();
            case AnfValue.Candidate c -> isFraction(c.provisionalLabel()) ? terms.shareAtNode()
                    : ids.local(DecisionTreeText.CANDIDATE_SCALE + c.provisionalLabel(), c.provisionalLabel()).orElse(0L);
            case AnfValue.Clarify c -> 0L;
        };
    }

    /** Whether a concept is a share's semantic: the share at a node, or the share of the subject group when the store holds the term. */
    static boolean isShare(long nid, DecisionTerms terms) {
        return nid == terms.shareAtNode() || terms.shareOfSubjectGroup() != 0 && nid == terms.shareOfSubjectGroup();
    }

    /** Whether a scale's name says the number is a share of its group: a share, a fraction, a proportion, a probability, a percentage. */
    static boolean isFraction(String label) {
        if (label == null) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.contains("fraction") || lower.contains("share") || lower.contains("proportion")
                || lower.contains("probabilit") || lower.contains("percent");
    }

    /**
     * How the parameter is named: the topic, then "among" the group the statement is about when
     * it names one and the number is a share, or the scale in brackets when it is not, so two
     * statements about one topic, the cost of an inspection and its sensitivity, read apart.
     */
    private static String name(AnfStatement statement, long semantic, Identities ids, DecisionTerms terms) {
        String topic = label(statement.topic());
        if (semantic == terms.shareAtNode()) {
            String group = statement.subjectOfInformation() == null ? "" : label(statement.subjectOfInformation());
            return group.isBlank() ? topic : topic + " among " + group;
        }
        String scale = semantic == 0 ? "" : ids.label(semantic);
        return scale == null || scale.isBlank() ? topic : topic + " (" + scale + ")";
    }

    private static String label(AnfValue value) {
        return switch (value) {
            case null -> "";
            case AnfValue.Grounded g -> g.label() == null || g.label().isBlank() ? g.identifier() : g.label();
            case AnfValue.Candidate c -> c.provisionalLabel();
            case AnfValue.Clarify c -> c.field();
        };
    }
}
