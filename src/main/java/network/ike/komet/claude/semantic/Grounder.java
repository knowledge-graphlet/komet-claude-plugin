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
package network.ike.komet.claude.semantic;

import java.util.Optional;

/**
 * Resolves a component identifier to a grounded slot, validating existence, active status, and —
 * when the field constrains it — the component's kind. This is the anti-hallucination boundary of
 * the semantic lift, abstracted behind an interface so the {@code emit_semantic} tool can be
 * unit-tested with a fake grounder (no store) and driven in production by a live
 * {@link dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator} (see {@link SemanticGrounding}).
 */
@FunctionalInterface
public interface Grounder {

    /**
     * Grounds an identifier, rejecting anything that does not resolve to an existing, active
     * component of the required kind.
     *
     * @param id           the SCTID, UUID, or comma-joined {@code PublicId} UUID array to resolve
     * @param requiredKind the kind the field constrains the referent to, or {@code null} when the
     *                     field accepts any component kind (the generic and collection families)
     * @return the grounded slot, or {@link Optional#empty()} when the id does not resolve, is
     *         retired, or is of the wrong kind
     */
    Optional<ComponentSlot.Grounded> ground(String id, ComponentSlot.Kind requiredKind);
}
