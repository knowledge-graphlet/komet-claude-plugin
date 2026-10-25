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

import dev.ikm.tinkar.terms.EntityFacade;

/**
 * One change to an existing concept's descriptions ({@code IKE-Network/ike-issues#1326}). A
 * description is named by its semantic, which must be an active description of the concept the
 * change is on. Acceptability is US English, the dialect the assistant proposes in.
 */
public sealed interface DescriptionOperation {

    /** Retires the description, and its US English acceptability with it. */
    record Retire(EntityFacade description) implements DescriptionOperation {
    }

    /** Changes the description's text. */
    record ChangeText(EntityFacade description, String text) implements DescriptionOperation {
    }

    /** Changes whether the description's case is significant. */
    record ChangeCaseSignificance(EntityFacade description, boolean caseSensitive) implements DescriptionOperation {
    }

    /** Makes the description preferred or acceptable in US English. */
    record ChangeAcceptability(EntityFacade description, boolean preferred) implements DescriptionOperation {
    }

    /** Adds a synonym: a regular name, preferred or acceptable in US English. */
    record AddSynonym(String text, boolean preferred, boolean caseSensitive) implements DescriptionOperation {
    }

    /** Adds a textual definition, preferred in US English. */
    record AddTextualDefinition(String text) implements DescriptionOperation {
    }
}
