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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.document.LocalConcepts;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTreeWrites;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.StructuredBlockKind;
import network.ike.komet.document.StructuredBlocks;
import network.ike.komet.claude.tools.GraphTools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a model block reads of its document when it is written ({@code dev-decision-point-knowledge},
 * settled 2026-11-06): the statements the document's {@code anf} blocks state, by the name each
 * block's {@code id=} gives, as the parameters read them and as the store identifies them, and
 * the module the block is written in. A statement is identified as the anf block's write
 * identifies it: the statement the block already holds, or the one it will hold once it is
 * saved, under the identity {@link network.ike.komet.claude.anf.AddAnfStatement} derives from
 * the block's element, so a model written before its statements references the same statements
 * the blocks write after.
 */
final class ModelDocument {

    private ModelDocument() {
    }

    /**
     * The document of a block, as a model block's write reads it.
     *
     * @param block the nid of the model block's element semantic
     * @param view  the view the statements are read under
     * @return the document; one with no statements when the block is not a stored document's
     */
    static DecisionTreeWrites.Document of(long block, ViewCalculator view) {
        Optional<StoredDocument> document = StructuredBlocks.documentOf(block);
        if (document.isEmpty()) {
            return DecisionTreeWrites.Document.NONE;
        }
        Map<String, AnfStatement> statements = new LinkedHashMap<>();
        Map<String, Long> blocks = new LinkedHashMap<>();
        for (StoredBlock stored : document.get().blocks().values()) {
            if (StructuredBlocks.kindOf(stored).isEmpty()) {
                continue;
            }
            StructuredBlockKind.Source source = StructuredBlocks.source(document.get(), stored);
            String info = DocumentBlockKinds.info(source.parameters());
            if (!AnfBlockRenderer.isTagged(info)) {
                continue;
            }
            Optional<String> name = AnfBlockRenderer.idOf(info);
            if (name.isEmpty() || source.content().isBlank()) {
                continue;
            }
            blocks.put(name.get(), stored.nid());
            try {
                AnfStatement statement = AnfAdoc.parse(AnfBlockRenderer.asAdoc(info, source.content()),
                        id -> GraphTools.resolveConcept(id, view));
                if (statement != null) {
                    statements.put(name.get(), statement);
                }
            } catch (RuntimeException unreadable) {
                // the block's own row says it could not be read; a parameter that reads it waits
            }
        }
        return of(statements, blocks, StructuredBlocks.moduleOf(block, view), view);
    }

    /**
     * A document from its statements and the blocks that state them.
     *
     * @param statements the statements by name
     * @param blocks     the nid of the anf block's element semantic, by the same name
     * @param module     the module the model block is written in
     * @param view       the view
     * @return the document
     */
    static DecisionTreeWrites.Document of(Map<String, AnfStatement> statements, Map<String, Long> blocks, OptionalLong module,
                                          ViewCalculator view) {
        DecisionTerms terms = DecisionTerms.fromBindings();
        return new DecisionTreeWrites.Document(
                ids -> name -> Optional.ofNullable(statements.get(name)).flatMap(statement -> DocumentStatementMeasures.of(statement, ids, terms)),
                name -> Optional.ofNullable(blocks.get(name)).map(nid -> statementOf(nid, view)),
                module);
    }

    /**
     * The statement an anf block holds, or will hold: the first statement of the ANF statement
     * pattern about the block that is visible in the view, else the identity the block's first
     * statement takes when the block is saved.
     *
     * @param block the nid of the anf block's element semantic
     * @param view  the view
     * @return the statement's public id
     */
    static PublicId statementOf(long block, ViewCalculator view) {
        for (SemanticEntity<SemanticEntityVersion> statement : EntityService.get()
                .semanticsForComponentOfPattern(block, IkePattern.ANF_STATEMENT_PATTERN.nid()).toList()) {
            if (view.latest(statement.nid()).isPresent()) {
                return statement.publicId();
            }
        }
        return LocalConcepts.derived(PrimitiveData.publicId(block), "anf-statement|0");
    }
}
