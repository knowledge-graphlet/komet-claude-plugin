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
package network.ike.komet.claude.anf.editor;

import dev.ikm.komet.framework.controls.MarkTooltip;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;

/**
 * What each mark of a statement or a complex concept says when the pointer rests on it
 * (IKE-Network/ike-issues#812): the mark's name on a line of its own, then what it means. A mark
 * says the same thing wherever it is drawn, in a document's line, in a form, beside a tree's node,
 * so its words are here once.
 *
 * <p>A mark stands in a row, a value or a badge that has a tooltip of its own, and the pointer at
 * rest on the mark is to show the mark's. {@link MarkTooltip} sees to that; a tooltip installed
 * plainly would never be seen.
 */
public final class MarkMeaning {

    /** The assertion sign at the head of a statement, or beside what a statement is cited for. */
    public static final String STATEMENT = "Statement\n"
            + "The assertion sign: what it stands before is asserted about one topic. A statement says what was"
            + " observed, done or asked for, with its result and its timing.";

    /** The question mark in a ring before words the knowledge base has nothing for. */
    public static final String WORDS_ONLY = "Words only\n"
            + "The knowledge base has nothing for these words yet. What they become, a concept, a complex concept"
            + " or a statement, is settled when they are resolved.";

    /** The identicon after a statement's sign. */
    public static final String STATEMENT_IDENTITY = "This statement\n"
            + "Its identicon, drawn from the identity the knowledge base gives the statement: the same picture"
            + " wherever this statement is named.";

    /** The empty place where a statement's identicon would stand. */
    public static final String NO_IDENTITY = "No identity yet\n"
            + "The knowledge base does not have this statement, so it has no identicon. It is given its identity,"
            + " and its identicon, when the statement is written to the knowledge base.";

    /** The dotted bar beside a complex concept's prerequisites. */
    public static final String MUST_HOLD = "What must hold\n"
            + "A prerequisite of the complex concept: it has to be so for the concept to apply."
            + " It is not something that was found.";

    /** The cross on a tree's alternative. */
    public static final String REJECTED = "Rejected alternative\n"
            + "The rollback chose another alternative.";

    private MarkMeaning() {
    }

    /**
     * Gives a mark its meaning, shown when the pointer rests on the mark whatever the mark stands in.
     *
     * @param mark    the mark
     * @param meaning one of this class's meanings
     * @param more    what else is to be said of this mark where it stands, each on lines of its own
     * @return the tooltip
     */
    public static Tooltip install(Node mark, String meaning, String... more) {
        StringBuilder says = new StringBuilder(meaning);
        for (String line : more) {
            if (line != null && !line.isBlank()) {
                says.append("\n\n").append(line.strip());
            }
        }
        return MarkTooltip.install(mark, says.toString());
    }

    /**
     * What a mark says, for a test or a caller that reads it back.
     *
     * @param mark a node
     * @return the words of the tooltip the node has, or empty when it has none
     */
    public static String of(Node mark) {
        return mark != null && mark.getProperties().get("javafx.scene.control.Tooltip") instanceof Tooltip tooltip
                ? tooltip.getText() : "";
    }
}
