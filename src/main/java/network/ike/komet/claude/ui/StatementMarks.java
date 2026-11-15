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

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import network.ike.komet.claude.anf.editor.StatementSign;

import java.util.List;

/**
 * What stands beside a tree's node or a finding for the statements it cites
 * (IKE-Network/ike-issues#812): the statement's sign once, then the identicon of each statement
 * cited, as a row of STAMP identicons takes one pentagon. The identicon is the one at the head of
 * the statement's own line, so the mark says which statement is cited, not only that one is. A
 * statement the knowledge base does not have yet has an empty place in the row.
 */
final class StatementMarks {

    private StatementMarks() {
    }

    /**
     * The sign and the identicons of the statements named.
     *
     * @param names   the statements' names, as the document's anf blocks name them
     * @param context what the document's blocks have made known, the statements' identities among it
     * @param base    the size of the text the mark stands beside
     * @return the sign, then each statement's identicon or its empty place
     */
    static Node graphic(List<String> names, ProfileBlocks context, double base) {
        HBox graphic = new HBox(2, StatementSign.plain(base * 0.95));
        graphic.setAlignment(Pos.CENTER_LEFT);
        for (String name : names) {
            graphic.getChildren().add(StatementSign.identityPlain(context.statementIdentity(name), base * 0.95));
        }
        return graphic;
    }
}
