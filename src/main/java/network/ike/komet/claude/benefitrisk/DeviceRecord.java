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
 * A {@code [device-record]} block: what a record of the Device Extension would state about a device,
 * so that a finding about a neighboring device can reach it.
 *
 * <p>For a device that is not a diagnostic test, the record states the attributes planned for such
 * devices, which DeX does not hold yet: the device's materials, the family they belong to, and the
 * hazards its design brings. For a diagnostic test it states what DeX's test records hold, the
 * targets the test detects, with the family they belong to, which is the organism, and the hazards
 * its design brings. A record names materials with {@code material:} lines and targets with
 * {@code target:} lines, under one {@code device:} root; a record that names targets and no
 * materials is a test record.
 *
 * @param device     the device
 * @param materials  its materials
 * @param targets    the targets it detects, for a diagnostic test
 * @param family     the family of its materials or targets, when one is stated
 * @param relatives  the members of that family
 * @param hazards    the hazards its design brings
 * @param statements what the record states about contact and use, in words
 * @param problems   what could not be read
 */
public record DeviceRecord(Optional<Named> device, List<Named> materials, List<Named> targets, Optional<Named> family,
                           Set<String> relatives, List<Named> hazards, List<String> statements, List<String> problems) {

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

    /** What a finding is filed against, and the words a reason gives it. */
    private enum Filed {
        MATERIAL("material", "materials"),
        TARGET("target", "targets");

        private final String word;
        private final String plural;

        Filed(String word, String plural) {
            this.word = word;
            this.plural = plural;
        }
    }

    /** Reads a block's content. */
    public static DeviceRecord parse(String body) {
        BlockLines.Parsed lines = BlockLines.parse(body);
        List<String> problems = new ArrayList<>(lines.problems());
        Optional<Named> device = Optional.empty();
        List<Named> materials = new ArrayList<>();
        List<Named> targets = new ArrayList<>();
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
                    case "target" -> named(child).ifPresent(targets::add);
                    case "hazard" -> named(child).ifPresent(hazards::add);
                    case "family" -> {
                        family = named(child);
                        child.attribute("members").ifPresent(members -> relatives.addAll(List.of(members.split(";"))));
                    }
                    case "states" -> statements.add(Tokens.unquote(child.rest()));
                    default -> problems.add("line " + child.number() + ": a device record states materials or targets, a family, hazards and statements: "
                            + child.rest());
                }
            }
        }
        return new DeviceRecord(device, materials, targets, family, relatives, hazards, statements, problems);
    }

    private static Optional<Named> named(BlockLines.Line line) {
        return Tokens.concept(line.head()).map(c -> new Named(c.identifier().orElse(c.key()), c.label()));
    }

    /** Whether this is the record of a diagnostic test: it names targets, and no materials. */
    public boolean isTestRecord() {
        return !targets.isEmpty() && materials.isEmpty();
    }

    /**
     * Why a finding reaches this device, if it does: it is filed by the hazard it shows, so it
     * reaches a device that brings that hazard, in any joint and of any material or target; a
     * finding about one of the device's own materials or targets reaches it whatever the hazard.
     * The reason speaks of what the finding is filed against: a target, or else a material, unless
     * it names neither and this is a test record.
     *
     * @param finding the finding
     * @return why, or empty when it does not reach the device
     */
    public Optional<String> reaches(Finding finding) {
        Filed filed = finding.target().isPresent() || finding.material().isEmpty() && isTestRecord() ? Filed.TARGET : Filed.MATERIAL;
        List<Named> mine = filed == Filed.TARGET ? targets : materials;
        Optional<String> against = (filed == Filed.TARGET ? finding.target() : finding.material()).map(Named::id);
        boolean own = against.filter(id -> mine.stream().anyMatch(m -> m.id().equals(id))).isPresent();
        boolean relative = !own && against.filter(relatives::contains).isPresent();
        boolean hazard = finding.hazard().filter(h -> hazards.stream().anyMatch(m -> m.id().equals(h.id()))).isPresent();
        if (hazard && own) {
            return Optional.of("Same " + filed.word + ", same hazard");
        }
        if (hazard && relative) {
            return Optional.of("Same hazard; " + familyName(filed) + ", the family of its " + filed.plural);
        }
        if (hazard) {
            return Optional.of("Same hazard, another " + filed.word);
        }
        if (own) {
            return Optional.of(filed == Filed.MATERIAL ? "Same material, another use"
                    : finding.hazard().isPresent() ? "Same target, another hazard" : "Same target");
        }
        return Optional.empty();
    }

    /**
     * The family's name in a reason. A family of materials is a common noun, written in lower case;
     * a family of targets is an organism, whose name keeps its capitals.
     */
    private String familyName(Filed filed) {
        String name = family.map(Named::name).orElse("one family");
        return filed == Filed.MATERIAL ? name.toLowerCase(java.util.Locale.ROOT) : name;
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
