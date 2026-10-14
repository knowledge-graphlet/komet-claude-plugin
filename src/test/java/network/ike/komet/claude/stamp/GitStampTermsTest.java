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
package network.ike.komet.claude.stamp;

import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.identity.StampCoordinates;
import design.knowledge.stamp.adoc.identity.Uuid5;
import design.knowledge.stamp.adoc.model.Person;
import design.knowledge.stamp.adoc.model.Status;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitStampTermsTest {

    @Test
    void namespaceIsFrozen() {
        assertEquals(UUID.fromString("2398b4ac-4e8a-4141-92a7-636d10f01e97"), GitIdentityAdapter.NAMESPACE,
                "the git identity namespace is frozen: changing it forks every identity already written to a store");
    }

    @Test
    void coreIdentitiesMatchTinkarsUuidGenerator() {
        for (String key : List.of("author:kec@knowledge.design", "author:stamp.versioned@knowledge.design",
                "stamp:4c39414f0e6b2c1d9a8b7c6d5e4f3a2b1c0d9e8f", "module:anf", "path:main", "path:feature/issue-42", "ünïcödé:✓")) {
            assertEquals(UuidT5Generator.get(GitIdentityAdapter.NAMESPACE, key), Uuid5.of(GitIdentityAdapter.NAMESPACE, key), key);
        }
    }

    @Test
    void conceptCarriesTheIdentityAndItsName() {
        Identity kerry = GitIdentityAdapter.raw().author(new Person("Kerry Goetz", "kerry@example.test"));

        ConceptFacade concept = GitStampTerms.concept(kerry);

        assertEquals(kerry.uuid(), concept.publicId().asUuidArray()[0]);
        assertEquals("Kerry Goetz", concept.description());
    }

    @Test
    void gitStatusIsAnActiveVersion() {
        assertEquals(State.ACTIVE, GitStampTerms.state(Status.ACTIVE));
    }

    @Test
    void gitStampCarriesAllFiveCoordinates() {
        GitIdentityAdapter adapter = GitIdentityAdapter.raw();
        Instant when = Instant.parse("2026-10-11T18:30:00Z");
        StampCoordinates coordinates = new StampCoordinates(adapter.stamp("4c39414f0e6b"), Status.ACTIVE, when,
                adapter.author(new Person("Keith E Campbell", "kec@knowledge.design")), adapter.module("anf"), adapter.path("main"));

        GitStampTerms.GitStamp stamp = GitStampTerms.GitStamp.of(coordinates);

        assertEquals("4c39414", stamp.stamp().description());
        assertEquals(State.ACTIVE, stamp.state());
        assertEquals(when.toEpochMilli(), stamp.time());
        assertEquals(adapter.author(new Person("", "KEC@knowledge.design")).uuid(), stamp.author().publicId().asUuidArray()[0]);
        assertEquals("anf", stamp.module().description());
        assertEquals("main", stamp.path().description());
    }
}
