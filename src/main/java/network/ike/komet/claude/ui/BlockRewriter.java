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

/**
 * What a rendered block calls to restate itself in the document it was read from: the owner of
 * the text finds the fenced block with this info and literal and writes the new ones in its
 * place. A what-if on a decision tree stays a what-if until the reader asks for this; a surface
 * with no rewriter offers the what-if alone.
 */
@FunctionalInterface
public interface BlockRewriter {

    /**
     * Restates one fenced block.
     *
     * @param info       the block's info string as rendered
     * @param literal    the block's body as rendered
     * @param newInfo    the info string to write
     * @param newLiteral the body to write
     */
    void rewrite(String info, String literal, String newInfo, String newLiteral);
}
