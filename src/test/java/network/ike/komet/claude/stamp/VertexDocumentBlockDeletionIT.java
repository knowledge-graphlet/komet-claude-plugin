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

import org.junit.jupiter.api.TestInstance;

/**
 * Every deletion scenario of {@link DocumentBlockDeletionIT}, over documents materialized as
 * semantic vertices (IKE-Network/ike-issues#1313): the block leaves its parent's Members with the
 * blank beside it, wherever that blank sits in the tree, and gets an inactive pending version.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexDocumentBlockDeletionIT extends DocumentBlockDeletionIT {

    @Override
    String model() {
        return "vertex";
    }
}
