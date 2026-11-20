/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.rules;

import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.rules.StoredRules.Result;
import network.ike.komet.document.LocalConcepts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.UUID;

import static network.ike.komet.claude.rules.RuleSourceFixture.RULE_NAME;
import static network.ike.komet.claude.rules.RuleSourceFixture.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A knowledge base that lacks the rule model (IKE-Network/ike-issues#1391), as every knowledge
 * base made before the model was declared does: it keeps no rule sets, it says so, and asking
 * leaves nothing behind in it. The store is a memory-only store with nothing loaded.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoredRulesWithoutTheRuleModelIT {

    @BeforeAll
    void startAnEmptyStore() {
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
    }

    @AfterAll
    void stopStore() {
        PrimitiveData.stop();
    }

    @Test
    void nothingIsReadNothingIsWrittenAndTheCallerIsTold() {
        StoredRules stored = new StoredRules(Calculators.View::Default, Coordinates.Edit::Default);
        String name = "Rules this knowledge base cannot keep";

        assertFalse(StoredRules.patternPresent());
        assertFalse(stored.modelPresent());
        UUID id = stored.identity(name);
        assertEquals(LocalConcepts.uuid(name), id, "the name still gives the rule set its identity");

        assertEquals(Result.NO_RULE_MODEL, stored.keep(new RuleSource(id, name, source()), List.of(RULE_NAME)).result());
        assertFalse(stored.kept(id));
        assertEquals(Result.NO_RULE_MODEL, stored.remove(id).result());
        assertEquals(List.of(), StoredRules.read(Calculators.View.Default()));
        assertEquals(List.of(), StoredRules.loadInto(() -> {
            throw new AssertionError("the rule service is not asked for when no rule set is kept");
        }, Calculators.View.Default(), new AcceptedRules(() -> {
            throw new AssertionError("nor is the record of what was accepted here");
        })));
        assertEquals(java.util.Optional.empty(), stored.keptText(id));

        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(id)), "no concept was written");
        assertFalse(PrimitiveData.get().hasPublicId(IkePattern.RULE_SOURCE_PATTERN.publicId()),
                "and asking did not make the knowledge base hold an identity for the pattern");
        assertFalse(PrimitiveData.get().hasPublicId(IkeConcept.KOMET_RULE_SET.publicId()));
    }
}
