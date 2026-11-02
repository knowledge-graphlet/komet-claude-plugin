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
package network.ike.komet.claude.complexconcept;

import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Focused;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Spec;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The findings on a reply's complex-concept blocks, as they go back to the assistant. */
class ComplexConceptFeedbackTest {

    @Test
    void aCleanBlockSendsNothingBack() {
        String reply = "Here it is.\n\n```complex-concept\n" + ComplexConceptTextTest.DECISION + "```\n\nTwo sentences.\n";
        assertEquals(1, ComplexConceptFeedback.blocks(reply).size());
        assertEquals(Optional.empty(), ComplexConceptFeedback.message(reply, BlockIdentities.storeFree()));
        assertTrue(ComplexConceptFeedback.blocks("```decision-tree\ndecision: k:A[A] @id=a\n```\n").isEmpty(),
                "a decision tree is not a complex concept");
    }

    @Test
    void theBadgeFormInsideTheBlockIsAFindingAndStillReadsTheIdentifier() {
        String reply = """
                ```complex-concept
                concept: k:Decision[Submission decision point] @id=rda-decision-point
                  expansion: focused k:SubmissionDecision[Marketing submission decision] @id=rda-submission-decision
                    prerequisite: k:FirstOfKind[Device is first of its kind] @id=rda-first-of-kind
                      value = [1,1] k:sctid=705057003[Presence]
                ```
                """;
        Optional<String> message = ComplexConceptFeedback.message(reply, BlockIdentities.storeFree());
        assertTrue(message.isPresent());
        assertTrue(message.get().startsWith(ComplexConceptFeedback.PREFIX), message.get());
        assertTrue(message.get().contains("Koncept Badge form, k:sctid=705057003"), message.get());
        assertTrue(message.get().contains("Load the Complex concept skill"), message.get());
        assertTrue(DecisionTreeFeedback.isFeedback(message.get()), "a transcript knows the turn as Komet's");
        Spec spec = ComplexConceptText.parse(ComplexConceptFeedback.blocks(reply).get(0).body(), BlockIdentities.storeFree())
                .spec().orElseThrow();
        Focused focused = (Focused) spec.shape().orElseThrow();
        assertEquals(Optional.of("705057003"), focused.prerequisites().get(0).unit().orElseThrow().token().identifier(),
                "the value after = is the identifier the badge form meant");
        assertEquals("Presence", focused.prerequisites().get(0).unit().orElseThrow().label());
    }

    @Test
    void aFocusNamingTheConceptItselfGoesBack() {
        String reply = """
                ```complex-concept
                concept: k:Thing[A thing] @id=thing
                  expansion: focused k:Thing[] @id=thing
                    prerequisite: k:Condition[A condition] @id=condition
                      value = [1,1] k:Presence[presence] @id=presence
                ```
                """;
        Optional<String> message = ComplexConceptFeedback.message(reply, BlockIdentities.storeFree());
        assertTrue(message.isPresent());
        assertTrue(message.get().contains("the focus is the complex concept itself"), message.get());
        assertFalse(message.get().contains("local to the block"), "the local-symbols note informs, it is not sent");
    }
}
