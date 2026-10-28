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
package network.ike.komet.claude.block;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * How a block's text and the store name the same components: a written identifier resolves to a
 * nid on parse, and a nid writes as its identifier and its label on emit. The live surface backs
 * this with the view; a structural test backs it with a map. A block round-trips by identity,
 * never by label: the label is a human aid and is recomputed on every emit.
 */
public interface Identities {

    /** The nid a written identifier names in the store, if it resolves. */
    OptionalLong nid(String identifier);

    /** The identifier a component writes as, if the store knows it. */
    Optional<String> identifier(long nid);

    /** The label a component writes beside its identifier. */
    String label(long nid);

    /**
     * An identity local to the text being read, for an identifier the store lacks, when this
     * implementation admits one: the same identifier gets the same identity for as long as the
     * text is read, so the text's own references hold, and the label is what it shows as. Empty
     * when nothing is local here, which is the default; see {@link BlockIdentities}.
     */
    default OptionalLong local(String identifier, String label) {
        return OptionalLong.empty();
    }

    /** Whether a nid is one made local to the text, so it names nothing in the store. */
    default boolean isLocal(long nid) {
        return false;
    }
}
