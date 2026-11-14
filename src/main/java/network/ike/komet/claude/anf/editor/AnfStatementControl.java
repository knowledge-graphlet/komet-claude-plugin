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
package network.ike.komet.claude.anf.editor;

import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.komet.kview.controls.KLDiTreeControl;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Skin;

import java.util.List;

/**
 * Edits an ANF statement as a tree: the DiTree control with the ANF statement adaptor layer
 * (IKE-Network/ike-issues#1312). Its value is the statement's tree, the single DiTree field of the
 * ANF statement pattern; its skin, {@link AnfStatementControlSkin}, extends the axiom control's
 * editing skin, so the context, the circumstance and every attribute render as the axiom control's
 * own rows, a nested logical expression (topic, subject of information, purpose) is the
 * axiom control itself, and structural move and undo come with it.
 *
 * <p>Like the axiom control, it edits only when a component search factory and a component item
 * resolver are set; without them it renders the statement read-only.</p>
 */
public class AnfStatementControl extends KLDiTreeControl {

    private final ReadOnlyObjectWrapper<String> lastRefusal = new ReadOnlyObjectWrapper<>(this, "lastRefusal");
    private final ObservableList<String> missing = FXCollections.observableArrayList();
    private final BooleanProperty showsMissing = new SimpleBooleanProperty(this, "showsMissing", true);
    private final BooleanProperty envelopeFromContext = new SimpleBooleanProperty(this, "envelopeFromContext", false);

    /**
     * Writes to the knowledge base for a value of the statement that has words and no concept:
     * a host that can write supplies one, and the control then offers to make a concept from the
     * words, or to add the words to a concept as an additional description. Without one the
     * control only finds concepts.
     */
    public interface ConceptWriter {

        /**
         * Makes a concept the knowledge base lacks, uncommitted. A concept the knowledge base
         * already has under exactly that name is that concept, and is returned as it is.
         *
         * @param name   the concept's fully qualified name
         * @param parent what it is a kind of
         * @return the concept
         * @throws RuntimeException with a reason to show the person, when it cannot be written
         */
        ConceptFacade create(String name, ConceptFacade parent);

        /**
         * The dialects of the knowledge base a description can be given an acceptability in, by
         * the names a person chooses among, the one to offer first at the head.
         *
         * @return the dialects' names; none when no description can be added
         */
        List<String> dialects();

        /**
         * Adds an additional description to a concept, uncommitted: a regular name, with an
         * acceptability in one dialect. A regular name that is to be preferred takes the place
         * of the one preferred in that dialect now, which becomes acceptable. A regular name the
         * concept already has in that dialect is left as it is.
         *
         * @param concept   the concept
         * @param text      the description's text
         * @param dialect   the dialect, one of {@link #dialects()}
         * @param preferred whether the description is preferred in the dialect, or acceptable
         * @throws RuntimeException with a reason to show the person, when it cannot be written
         */
        void addDescription(ConceptFacade concept, String text, String dialect, boolean preferred);
    }

    private ConceptWriter conceptWriter;

    /** @return what writes a new concept or another name for the control, or null when its host cannot write */
    public final ConceptWriter getConceptWriter() {
        return conceptWriter;
    }

    /** @param writer what writes a new concept or another name for the control; null when its host cannot write */
    public final void setConceptWriter(ConceptWriter writer) {
        this.conceptWriter = writer;
    }

    /** Creates an empty ANF statement control. */
    public AnfStatementControl() {
        getStyleClass().add("anf-statement-control");
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AnfStatementControlSkin(this);
    }

    /**
     * How tall the statement stands depends on how wide it is given to be: the words of a value
     * that has no concept yet are shown in full, on as many lines as they need.
     *
     * @return horizontal
     */
    @Override
    public javafx.geometry.Orientation getContentBias() {
        return javafx.geometry.Orientation.HORIZONTAL;
    }

    /**
     * The message of the last edit the ANF builder refused, or null since the last edit it
     * accepted. A refused edit leaves the tree as it was.
     *
     * @return the refusal message property
     */
    public final ReadOnlyObjectProperty<String> lastRefusalProperty() {
        return lastRefusal.getReadOnlyProperty();
    }

    /** @return the message of the last refused edit, or null */
    public final String getLastRefusal() {
        return lastRefusal.get();
    }

    void setLastRefusal(String message) {
        lastRefusal.set(message);
    }

    /**
     * What the statement still lacks to be complete, by the structural definition: a tree under
     * construction is well formed without these, and the control lists them as missing rows.
     *
     * @return the missing parts, live
     */
    public final ObservableList<String> getMissing() {
        return FXCollections.unmodifiableObservableList(missing);
    }

    void setMissing(List<String> parts) {
        missing.setAll(parts);
    }

    /**
     * Whether the control lists what the statement still lacks under the tree. True by default;
     * a statement embedded as a definitional template, such as a complex concept's prerequisite,
     * turns it off, since it never carries a subject of record or a statement time.
     *
     * @return the property
     */
    public final BooleanProperty showsMissingProperty() {
        return showsMissing;
    }

    /** @return whether the control lists what the statement still lacks */
    public final boolean isShowsMissing() {
        return showsMissing.get();
    }

    /** @param shows whether the control lists what the statement still lacks */
    public final void setShowsMissing(boolean shows) {
        showsMissing.set(shows);
    }

    /**
     * Whether the statement's surroundings supply its subject of record and statement time, as a
     * note does for the statements lifted from it. When true, the control does not list those two
     * as missing: the statement states them only where it differs from its note. False by default.
     *
     * @return the property
     */
    public final BooleanProperty envelopeFromContextProperty() {
        return envelopeFromContext;
    }

    /** @return whether the surroundings supply the subject of record and statement time */
    public final boolean isEnvelopeFromContext() {
        return envelopeFromContext.get();
    }

    /** @param supplied whether the surroundings supply the subject of record and statement time */
    public final void setEnvelopeFromContext(boolean supplied) {
        envelopeFromContext.set(supplied);
    }
}
