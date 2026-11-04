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
package network.ike.komet.claude.ui;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.anf.AnfFeedback;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.complexconcept.ComplexConceptFeedback;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.ViewIdentities;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.StructuredBlockKind;
import network.ike.komet.claude.stamp.StructuredBlocks;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What Komet finds when it reads structured blocks: in the words the assistant reads, whether the
 * blocks came in a reply, which the transcript sends back as a correction turn, or stand in a
 * document, whose findings a tool answers with (ike-lab-documents topic
 * {@code dev-decision-document}, "Findings in the tool result"). A document's blocks are read
 * together, as its context reads them, so a tree's statements and concepts resolve wherever in the
 * document they stand.
 */
public final class DocumentFindings {

    private DocumentFindings() {
    }

    /**
     * The findings on a reply's fenced blocks.
     *
     * @param reply the reply's Markdown
     * @param view  the view identifiers are read under
     * @return the findings, empty when every block reads clean or there are none
     */
    public static Optional<String> ofReply(String reply, ViewCalculator view) {
        Identities ids = new BlockIdentities(new ViewIdentities(view));
        String joined = Stream.of(DecisionTreeFeedback.message(reply, DecisionTerms.fromBindings(), ids),
                        ComplexConceptFeedback.message(reply, ids), AnfFeedback.message(reply))
                .flatMap(Optional::stream).collect(Collectors.joining("\n\n"));
        return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
    }

    /**
     * The findings on a document's structured blocks.
     *
     * @param document the document, its pending edit included
     * @param view     the view identifiers are read under
     * @return the findings, empty when every block reads clean or there are none
     */
    public static Optional<String> of(StoredDocument document, ViewCalculator view) {
        return ofReply(fenced(document), view).map(DocumentFindings::forTool);
    }

    /** A correction turn's words as a tool's result: the block is in the document, and is corrected there. */
    static String forTool(String findings) {
        return findings.replace(" in your reply and found:", " in the document and found:")
                .replaceAll("(?m)^Restate your whole answer.*$", "Correct them now with propose_block_edit, changing only what the "
                        + "findings name and keeping every identity and every number the person gave.");
    }

    /** A document's structured blocks as the fenced blocks of a reply, in document order. */
    static String fenced(StoredDocument document) {
        StringBuilder fenced = new StringBuilder();
        for (StoredBlock block : document.blocks().values()) {
            if (StructuredBlocks.kindOf(block).isPresent()) {
                StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
                fenced.append("```").append(DocumentBlockKinds.info(source.parameters())).append('\n')
                        .append(source.content()).append("\n```\n\n");
            }
        }
        return fenced.toString();
    }
}
