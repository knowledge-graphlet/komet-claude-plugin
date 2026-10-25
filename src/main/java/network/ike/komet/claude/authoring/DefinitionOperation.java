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
package network.ike.komet.claude.authoring;

import dev.ikm.tinkar.terms.ConceptFacade;

import java.util.List;

/**
 * One surgical change to an existing concept's stated definition ({@code IKE-Network/ike-issues#1326}).
 * Each names what it changes by the concepts in it, never by a position in the tree; what no
 * operation names is left as it is stored, vertex identity included.
 *
 * <p>A set is chosen by its kind and, when a definition has more than one of that kind, its
 * ordinal ({@link SetSelector}). A role group is named by exactly the roles it holds. An
 * operation that names a supertype, role or group the definition does not hold, or one it holds
 * in more than one place, is refused.
 */
public sealed interface DefinitionOperation {

    /** An existential role: {@code type = filler}. */
    record Role(ConceptFacade type, ConceptFacade filler) {
    }

    /**
     * A definition set: the {@code ordinal}-th (from 0) necessary or sufficient set.
     *
     * @param sufficient a sufficient set when true, a necessary one when false
     * @param ordinal    which of the sets of that kind, in stored order
     */
    record SetSelector(boolean sufficient, int ordinal) {
        /** The first necessary set, where a primitive concept's definition is. */
        public static final SetSelector NECESSARY = new SetSelector(false, 0);
        /** The first sufficient set. */
        public static final SetSelector SUFFICIENT = new SetSelector(true, 0);
    }

    /** Adds a supertype to the set; the first set of a kind is made when there is none. */
    record AddSupertype(SetSelector set, ConceptFacade concept) implements DefinitionOperation {
    }

    /** Removes a supertype from the one set that holds it. */
    record RemoveSupertype(ConceptFacade concept) implements DefinitionOperation {
    }

    /** Replaces a supertype, in the one set that holds it, with another. */
    record ReplaceSupertype(ConceptFacade from, ConceptFacade to) implements DefinitionOperation {
    }

    /** Adds an ungrouped role to the set; the first set of a kind is made when there is none. */
    record AddRole(SetSelector set, Role role) implements DefinitionOperation {
    }

    /** Removes an ungrouped role from the one set that holds it. */
    record RemoveRole(Role role) implements DefinitionOperation {
    }

    /** Adds a role group of these roles to the set; the first set of a kind is made when there is none. */
    record AddGroup(SetSelector set, List<Role> roles) implements DefinitionOperation {
    }

    /** Removes the one role group that holds exactly these roles. */
    record RemoveGroup(List<Role> group) implements DefinitionOperation {
    }

    /** Adds a role to the one role group that holds exactly {@code group}. */
    record AddToGroup(List<Role> group, Role role) implements DefinitionOperation {
    }

    /** Removes a role from the one role group that holds exactly {@code group}. */
    record RemoveFromGroup(List<Role> group, Role role) implements DefinitionOperation {
    }

    /** Makes the set sufficient or necessary, keeping what it holds. */
    record SetKind(SetSelector set, boolean sufficient) implements DefinitionOperation {
    }
}
