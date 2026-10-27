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
 * The decision-tree profile of the property-graph block ({@code dev-decision-tree-blocks}): the
 * typed view of a stored decision tree ({@link network.ike.komet.claude.decision.DecisionTree}
 * over the generic {@code ProfiledTree}), its vertices
 * ({@link network.ike.komet.claude.decision.DecisionElement}), the terms it reads them by
 * ({@link network.ike.komet.claude.decision.DecisionTerms}, from the IKE starter set's
 * {@code DecisionTreeSet}), the ANF measure and its interval arithmetic
 * ({@link network.ike.komet.claude.decision.Measure}, {@link network.ike.komet.claude.decision.Bounds}),
 * the standpoint a tree is read from ({@link network.ike.komet.claude.decision.ValuationCoordinate}),
 * and the rollback that evaluates it ({@link network.ike.komet.claude.decision.Rollback}). The
 * block grammar, its parser and the renderers are the next step and are not here.
 */
package network.ike.komet.claude.decision;
