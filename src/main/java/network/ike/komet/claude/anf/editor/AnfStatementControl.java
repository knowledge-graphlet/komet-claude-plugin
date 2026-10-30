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

    /** Creates an empty ANF statement control. */
    public AnfStatementControl() {
        getStyleClass().add("anf-statement-control");
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AnfStatementControlSkin(this);
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
}
