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

import dev.ikm.komet.framework.controls.KonceptDisclosure;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.foundation.ike.bindings.IkePattern;

/** A concept with a complex-concept specification this view sees has more to it than its EL++ axioms. */
public final class ComplexConceptDisclosure implements KonceptDisclosure {

    /** Creates the provider the service loader finds. */
    public ComplexConceptDisclosure() {
    }

    @Override
    public boolean discloses(long nid, ViewCalculator calculator) {
        // a store without the pattern has no such concepts, and asking for its nid would mint one
        return PrimitiveData.get().hasPublicId(IkePattern.COMPLEX_CONCEPT_PATTERN.publicId())
                && ComplexConceptWrites.specificationOf(PrimitiveData.publicId(nid), calculator).isPresent();
    }
}
