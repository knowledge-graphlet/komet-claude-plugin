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

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A graphlet model as the document's blocks share it in memory: its id, the text of its block,
 * and the way a rendering writes a value into it. A rendering reads the text through
 * {@link DecisionTreeText} as it would its own; a value it commits is a restatement of one
 * line of the model's text, handed back to the block that holds the model, which writes it to
 * the document and publishes the model again. A model with no writer is read-only where it is
 * shown, and a rendering then offers a what-if of its own.
 *
 * @param id     the model's id, by which renderings name it
 * @param info   the model block's info string
 * @param body   the model block's text
 * @param writer takes the model's restated text, when the surface may write it
 */
public record GraphletModel(String id, String info, String body, Optional<Consumer<String>> writer) {

    /** Whether the surface this model is shown on may write it. */
    public boolean writable() {
        return writer.isPresent();
    }

    /**
     * Writes a value into the model: the line that states the parameter restated with the value
     * as an ordinary measure with equal bounds, or a value added to a parameter that ran at its
     * statement's midpoint.
     *
     * @param line   the one-based line of the model's text that states the parameter
     * @param bounds the value, with equal bounds
     * @return whether the model took it: false when it is not writable or the line states no parameter
     */
    public boolean write(int line, Bounds bounds) {
        if (writer.isEmpty()) {
            return false;
        }
        Optional<String> restated = DecisionTreeEdits.restate(body, line, bounds);
        if (restated.isEmpty() || restated.get().equals(body)) {
            return restated.isPresent();
        }
        writer.get().accept(restated.get());
        return true;
    }
}
