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
/**
 * The decision-tree figures without a toolkit ({@code dev-decision-tree-blocks}): the tree on
 * its {@link network.ike.komet.claude.decision.DecisionTreeLayout} and the tornado from its
 * {@link network.ike.komet.claude.decision.DecisionTreeSensitivity}, each composed into a
 * {@link network.ike.komet.claude.decision.render.DecisionFigure} of marks whose label widths
 * are estimated rather than measured, and written as self-contained SVG for the documentation
 * build ({@link network.ike.komet.claude.decision.render.DecisionTreeSvg}) or as Typst for the
 * print pipeline ({@link network.ike.komet.claude.decision.render.DecisionTreeTypst}). The
 * JavaFX surface in {@code network.ike.komet.claude.ui} draws the same conventions live; the
 * figure model, the text metrics, the writers and the tornado builder have store-free twins in
 * {@code ike-docs/koncept-asciidoc-extension}, kept word for word in step.
 */
package network.ike.komet.claude.decision.render;
