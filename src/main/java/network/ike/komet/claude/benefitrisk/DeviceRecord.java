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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.benefitrisk.Findings.Finding;
import network.ike.komet.claude.benefitrisk.Findings.Named;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@code [device-record]} block: what a record of the Device Extension would state about a device
 * for the attributes planned for devices that are not diagnostic tests, which DeX does not hold
 * yet. It names the device's materials, the family they belong to, and the hazards its design
 * brings, so that a finding about a neighboring device can reach it.
 *
 * @param device    the device
 * @param materials its materials
 * @param family    the family of materials, when one is stated
 * @param relatives the materials of that family
 * @param hazards   the hazards its design brings
 * @param statements what the record states about contact and use, in words
 * @param problems  what could not be read
 */
public record DeviceRecord(Optional<Named> device, List<Named> materials, Optional<Named> family, Set<String> relatives,
                           List<Named> hazards, List<String> statements, List<String> problems) {

    /** The block's style. */
    public static final String TAG = "device-record";

    /**
     * A finding that reaches this device, and why.
     *
     * @param finding the finding
     * @param why     why it reaches the device, in words
     */
    public record Reach(Finding finding, String why) {
    }

    /** Reads a block's content. */
    public static DeviceRecord parse(String body) {
        BlockLines.Parsed lines = BlockLines.parse(body);
        List<String> problems = new ArrayList<>(lines.problems());
        Optional<Named> device = Optional.empty();
        List<Named> materials = new ArrayList<>();
        Optional<Named> family = Optional.empty();
        Set<String> relatives = new java.util.LinkedHashSet<>();
        List<Named> hazards = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        if (lines.roots().size() != 1 || !"device".equals(lines.roots().getFirst().word())) {
            problems.add("A device record has one device at its root.");
        } else {
            BlockLines.Line root = lines.roots().getFirst();
            device = named(root);
            for (BlockLines.Line child : root.children()) {
                switch (child.word()) {
                    case "material" -> named(child).ifPresent(materials::add);
                    case "hazard" -> named(child).ifPresent(hazards::add);
                    case "family" -> {
                        family = named(child);
                        child.attribute("members").ifPresent(members -> relatives.addAll(List.of(members.split(";"))));
                    }
                    case "states" -> statements.add(Tokens.unquote(child.rest()));
                    default -> problems.add("line " + child.number() + ": a device record states materials, a family, hazards and statements: " + child.rest());
                }
            }
        }
        return new DeviceRecord(device, materials, family, relatives, hazards, statements, problems);
    }

    private static Optional<Named> named(BlockLines.Line line) {
        return Tokens.concept(line.head()).map(c -> new Named(c.identifier().orElse(c.key()), c.label()));
    }

    /**
     * Why a finding reaches this device, if it does: it is filed by the hazard it shows, so it
     * reaches a device that brings that hazard, in any joint and of any material; a finding about
     * one of the device's own materials reaches it whatever the use.
     *
     * @param finding the finding
     * @return why, or empty when it does not reach the device
     */
    public Optional<String> reaches(Finding finding) {
        Optional<String> material = finding.material().map(Named::id);
        boolean own = material.filter(id -> materials.stream().anyMatch(m -> m.id().equals(id))).isPresent();
        boolean relative = !own && material.filter(relatives::contains).isPresent();
        boolean hazard = finding.hazard().filter(h -> hazards.stream().anyMatch(mine -> mine.id().equals(h.id()))).isPresent();
        String family = this.family.map(Named::name).orElse("one family");
        if (hazard && own) {
            return Optional.of("Same material, same hazard");
        }
        if (hazard && relative) {
            return Optional.of("Same hazard; " + family.toLowerCase(java.util.Locale.ROOT) + ", the family of its materials");
        }
        if (hazard) {
            return Optional.of("Same hazard, another material");
        }
        if (own) {
            return Optional.of("Same material, another use");
        }
        return Optional.empty();
    }

    /** The findings in print by a year that reach this device, in the order written. */
    public List<Reach> reached(Findings findings, int year) {
        List<Reach> reached = new ArrayList<>();
        for (Finding finding : findings.inPrintBy(year)) {
            reaches(finding).ifPresent(why -> reached.add(new Reach(finding, why)));
        }
        return reached;
    }
}
