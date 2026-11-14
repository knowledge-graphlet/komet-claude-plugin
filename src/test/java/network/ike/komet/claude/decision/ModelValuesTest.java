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
package network.ike.komet.claude.decision;

import network.ike.komet.claude.block.BlockIdentities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The values of the jaw implant model as a reader meets them: the chance that a device is a
 * failing device under the chance node that reads it, with the three chances it multiplies
 * beneath it; the two chances of the wear test under theirs; and the two costs.
 */
class ModelValuesTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static String model;

    @BeforeAll
    static void readTheModel() throws IOException {
        model = GraphletModelTest.body("/decision/jaw-wear-test-model.adoc");
    }

    private static DecisionTree tree(String body, BlockIdentities ids) {
        return DecisionTreeText.parse(body, T, ids, ExternalQuantities.none(), StatementMeasures.none()).tree().orElseThrow();
    }

    /** The vertex of the parameter that states a quantity, by the quantity's identifier. */
    private static int value(DecisionTree tree, BlockIdentities ids, String identifier) {
        long nid = ids.nid(identifier).orElseThrow();
        for (DecisionElement.Parameter parameter : tree.nodesOfType(DecisionElement.Parameter.class)) {
            if (parameter.quantity().orElse(0) == nid) {
                return parameter.vertexIndex();
            }
        }
        throw new AssertionError("no parameter states " + identifier);
    }

    private static List<Integer> vertices(ModelValues.Group group) {
        return group.rows().stream().map(ModelValues.Row::vertex).toList();
    }

    @Test
    @DisplayName("Each value is under what the tree reads it for: a chance node by its name, or the costs")
    void theValuesAreGroupedByWhatReadsThem() {
        BlockIdentities ids = BlockIdentities.storeFree();
        DecisionTree tree = tree(model, ids);
        ModelValues values = ModelValues.of(tree, ids::label);
        assertEquals(8, values.count());
        List<ModelValues.Group> groups = values.groups();
        assertEquals(List.of(ModelValues.Kind.CHANCE, ModelValues.Kind.CHANCE, ModelValues.Kind.COSTS),
                groups.stream().map(ModelValues.Group::kind).toList());
        assertEquals("State of the implant material in patients", ids.label(groups.get(0).concept().orElseThrow()));
        // the tree has the wear test's result under two chance nodes of one name, one for each state of the device
        assertEquals("Wear test result", ids.label(groups.get(1).concept().orElseThrow()));
        assertEquals(List.of(value(tree, ids, "jaw-test-stops"), value(tree, ids, "jaw-test-errs")), vertices(groups.get(1)));
        assertEquals(List.of(value(tree, ids, "jaw-test-cost"), value(tree, ids, "jaw-care-cost")), vertices(groups.get(2)));
        assertTrue(groups.get(2).concept().isEmpty());
    }

    @Test
    @DisplayName("A value worked out from others is above them, and they are beneath it and in no group of their own")
    void aWorkedOutValueIsAboveTheValuesItReads() {
        BlockIdentities ids = BlockIdentities.storeFree();
        DecisionTree tree = tree(model, ids);
        ModelValues values = ModelValues.of(tree, ids::label);
        int failing = value(tree, ids, "jaw-failing");
        List<Integer> links = List.of(value(tree, ids, "jaw-link-a"), value(tree, ids, "jaw-link-b"), value(tree, ids, "jaw-link-c"));
        assertEquals(List.of(new ModelValues.Row(failing, 0), new ModelValues.Row(links.get(0), 1),
                new ModelValues.Row(links.get(1), 1), new ModelValues.Row(links.get(2), 1)), values.groups().get(0).rows());
        assertEquals(links, values.inputs(failing));
        assertEquals(links, values.beneath(failing));
        assertTrue(values.inputs(links.get(0)).isEmpty(), "a stated value is worked out from nothing");
    }

    @Test
    @DisplayName("A value goes with the values it is worked out with and the branches that read it")
    void aValueGoesWithValuesAndBranches() {
        BlockIdentities ids = BlockIdentities.storeFree();
        DecisionTree tree = tree(model, ids);
        ModelValues values = ModelValues.of(tree, ids::label);
        int failing = value(tree, ids, "jaw-failing");
        int wear = value(tree, ids, "jaw-link-a");
        int tissue = value(tree, ids, "jaw-link-b");
        int removal = value(tree, ids, "jaw-link-c");
        assertEquals(Set.of(failing, wear, tissue, removal), Set.copyOf(values.related(failing)));
        assertEquals(Set.of(wear, failing), Set.copyOf(values.related(wear)), "a chance goes with what it is multiplied into, not with its fellows");
        // the failing device's chance is read once under each alternative
        List<Integer> readers = values.readers(failing);
        assertEquals(2, readers.size());
        assertEquals(readers, values.readers(wear), "a value is read where the value worked out from it is read");
        Set<Integer> events = Set.of(GraphletModelTest.vertexOf(tree, ids, "jaw-failing-asked"),
                GraphletModelTest.vertexOf(tree, ids, "jaw-failing-cleared-state"));
        assertEquals(events, Set.of(tree.parentIndex(readers.get(0)).orElseThrow(), tree.parentIndex(readers.get(1)).orElseThrow()));
        // and a branch reads the values stated on it, with the values those are worked out from
        assertEquals(Set.of(failing, wear, tissue, removal),
                Set.copyOf(values.valuesAt(GraphletModelTest.vertexOf(tree, ids, "jaw-failing-asked"))));
        assertEquals(Set.of(failing, wear, tissue, removal, value(tree, ids, "jaw-care-cost")),
                Set.copyOf(values.valuesAt(GraphletModelTest.vertexOf(tree, ids, "jaw-failing-cleared-state"))));
        assertEquals(List.of(value(tree, ids, "jaw-test-cost")), values.valuesAt(GraphletModelTest.vertexOf(tree, ids, "jaw-ask")));
        assertTrue(values.valuesAt(GraphletModelTest.vertexOf(tree, ids, "jaw-clear")).isEmpty());
        // an event that is the rest of its chance node goes with the chances of the other events
        assertEquals(Set.of(failing, wear, tissue, removal),
                Set.copyOf(values.valuesAt(GraphletModelTest.vertexOf(tree, ids, "jaw-sound-asked"))));
        assertEquals(Set.of(value(tree, ids, "jaw-test-stops"), value(tree, ids, "jaw-care-cost")),
                Set.copyOf(values.valuesAt(GraphletModelTest.vertexOf(tree, ids, "jaw-holds"))));
    }

    @Test
    @DisplayName("A value no branch reads, and no value is worked out from, is said to be read by no branch")
    void aValueNothingReadsIsSaidSo() {
        BlockIdentities ids = BlockIdentities.storeFree();
        String body = model.replace("  alternative: k:AskTest", "  parameter = [0.4,0.4] k:Spare[Chance nothing reads] @id=jaw-spare basis=estimated\n  alternative: k:AskTest");
        DecisionTree tree = tree(body, ids);
        ModelValues values = ModelValues.of(tree, ids::label);
        assertEquals(9, values.count());
        ModelValues.Group last = values.groups().getLast();
        assertEquals(ModelValues.Kind.UNREAD, last.kind());
        assertEquals(List.of(value(tree, ids, "jaw-spare")), vertices(last));
        assertTrue(values.readers(value(tree, ids, "jaw-spare")).isEmpty());
    }
}
