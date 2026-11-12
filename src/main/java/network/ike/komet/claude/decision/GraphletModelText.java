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
package network.ike.komet.claude.decision;

import network.ike.komet.claude.block.Tokens;

import java.util.Optional;

/**
 * The {@code [graphlet-model]} block ({@code dev-decision-point-knowledge}, settled with KEC
 * 2026-11-06): the model of a decision as one versioned property graph, holding the tree's
 * structure and its parameters together. A graphlet is a versioned property graph stored as one
 * semantic, after the network-analysis sense of a small connected subgraph; "model" alone is
 * ambiguous, which is why the word qualifies it, and no other block takes the prefix. The block's
 * text is the decision tree's grammar as it stands ({@link DecisionTreeText}), plus a parameter
 * that reads one of the document's ANF statements by name and may hold the value the tree runs
 * at, an ordinary measure with equal bounds on the statement's own scale:
 *
 * <pre>
 * [graphlet-model, id=bimo-site-model]
 * ----
 * decision: k:SiteInspectionDecision[Inspect the site?] @id=bimo-site-decision
 *   parameter = [0.15,0.15] anf:est-material-problem
 *   parameter = anf:est-inspection-sensitivity
 *   alternative: k:Inspect[inspect] @id=bimo-inspect statement=anf:req-inspection
 *     money = from anf:est-inspection-cost
 *     …
 * ----
 * </pre>
 *
 * <p>A {@code [decision-tree, model=bimo-site-model]} or {@code [tornado, model=bimo-site-model]}
 * block is a rendering of the model: it holds no structure and no number, only how the model is
 * viewed, the standpoint it is rolled back from and the cohort it is counted out for. Values are
 * in the model, views on the rendering: while a slider is dragged its preview is seen in one
 * rendering alone, and letting go of it writes the model, so every rendering that names it
 * redraws. A model block names itself with {@code id=}, or by its root's {@code @id} when it
 * names none.
 */
public final class GraphletModelText {

    /** The block's style and language tag. */
    public static final String TAG = "graphlet-model";

    /** The attribute by which a rendering names the model it draws, {@code model=<id>}. */
    public static final String MODEL = "model";

    private GraphletModelText() {
    }

    /** Whether a fence's info string names the model block. */
    public static boolean isTagged(String info) {
        String tag = info == null ? "" : Tokens.words(info).stream().findFirst().orElse("");
        return TAG.equals(tag);
    }

    /**
     * The id a model block gives itself, {@code id=<id>} in its info, or the identifier its root
     * line carries, {@code @id=<identifier>}, when it gives none.
     */
    public static Optional<String> modelId(String info, String body) {
        Optional<String> own = DecisionTreeWrites.blockId(info);
        if (own.isPresent()) {
            return own;
        }
        String root = body == null ? "" : body.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        for (String word : Tokens.words(root)) {
            if (word.startsWith("@id=") && word.length() > 4) {
                return Optional.of(word.substring(4));
            }
        }
        return Optional.empty();
    }

    /** The model a rendering names, {@code model=<id>} in its info, when it names one. */
    public static Optional<String> modelReference(String info) {
        if (info == null) {
            return Optional.empty();
        }
        for (String word : Tokens.words(info)) {
            if (word.startsWith(MODEL + "=") && word.length() > MODEL.length() + 1) {
                return Optional.of(word.substring(MODEL.length() + 1).replace("\"", ""));
            }
        }
        return Optional.empty();
    }
}
