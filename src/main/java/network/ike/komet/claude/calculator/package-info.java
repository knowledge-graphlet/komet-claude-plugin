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
 * The calculator profile of the property-graph block ({@code dev-calculator-blocks}): a medical
 * calculator held as one tree ({@link network.ike.komet.claude.calculator.Calculator}), the
 * bindings of a document's ANF statements to its inputs
 * ({@link network.ike.komet.claude.calculator.Bindings}), the pure evaluation that carries their
 * bounds through the equation ({@link network.ike.komet.claude.calculator.Evaluation}), and the
 * one-way sweep that says which input moves the result most
 * ({@link network.ike.komet.claude.calculator.CalculatorSensitivity}). The profile mirrors the
 * decision package over the same measure, bases and derivation keys, and a decision tree reads
 * an evaluation's result as a parameter.
 */
package network.ike.komet.claude.calculator;
