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
 * A proof of concept of the three views of ike-lab-documents topic {@code dev-benefit-risk-blocks}:
 * a value tree, an effects table that takes its rows from the tree's leaves, and a forest plot of
 * the table. The blocks are read from their text and draw; nothing here writes to the store, and
 * the topic's decisions, the {@code estimate} vertex among them, are still open.
 */
package network.ike.komet.claude.benefitrisk;
