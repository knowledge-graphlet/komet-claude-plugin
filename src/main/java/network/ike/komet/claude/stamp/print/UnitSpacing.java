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
package network.ike.komet.claude.stamp.print;

import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;

/**
 * The space between units, in points, as Typst sets it for the writer's page — read off the
 * golden layouts of the fidelity corpus (2026-10-20) rather than guessed from the preamble:
 *
 * <ul>
 *   <li>paragraphs are {@code par.spacing} = 1.1em of the body apart; tight list items and table
 *       rows a leading (0.65em) apart, a table row's cells inset 5pt top and bottom besides;</li>
 *   <li>a heading's weak spacing — 0.9em of its own size above, 0.5em below — replaces the
 *       paragraph spacing next to it rather than competing with it; two weak spacings collapse
 *       to the larger;</li>
 *   <li>the title's {@code block(below: 1.2em)} is 1.2em of the body; a block title keeps 1em above
 *       and 0.5em below; a table's caption 1.1em of the body plus the first row's inset below;</li>
 *   <li>a raw block adds its 6.5pt inset above and below its lines, which are 1.09em apart.</li>
 * </ul>
 *
 * One place for the rule, so the surface's units and the model's agree.
 */
public final class UnitSpacing {

    /** The pitch of a raw block's lines as a fraction of its size. */
    public static final double VERBATIM_PITCH = 1.09;
    /** The inset a raw block keeps above and below its lines. */
    public static final double VERBATIM_INSET = 6.5;
    /** The inset a table cell keeps above and below its text. */
    public static final double CELL_INSET = 5;

    private UnitSpacing() { }

    /**
     * @param kind     the unit's kind
     * @param previous the kind of the unit before it, or {@code null} for the first
     * @param size     the unit's font size
     * @param base     the body size
     * @param caption  whether an {@link Kind#OTHER} unit is a table's caption (set above the table) rather than a block title
     * @return {@code {above, below}} in points; a positive {@code below} is a weak spacing of the unit's own
     */
    public static double[] of(Kind kind, Kind previous, double size, double base, boolean caption) {
        double above = switch (kind) {
            case TITLE -> 0;
            case HEADING -> size * 0.9;
            case LIST_ITEM -> previous == Kind.LIST_ITEM ? base * 0.65 : base * 1.1;
            case TABLE_ROW -> previous == Kind.TABLE_ROW ? base * 0.65 + 2 * CELL_INSET + 0.5 : base * 1.1;
            case OTHER -> base;
            default -> base * 1.1;
        };
        double below = switch (kind) {
            case TITLE -> base * 1.2;
            case HEADING -> size * 0.5;
            case OTHER -> caption ? base * 1.1 + CELL_INSET - 0.4 : base * 0.5;
            default -> 0;
        };
        return new double[] {above, below};
    }

    /** As {@link #of(Kind, Kind, double, double, boolean)} for a unit that is no caption. */
    public static double[] of(Kind kind, Kind previous, double size, double base) {
        return of(kind, previous, size, base, false);
    }

    /**
     * The space from the previous unit's last baseline to this unit's first line's top (its cap
     * height), as Typst leaves it: the previous unit's weak spacing where it has one, this
     * unit's otherwise, the larger when both are weak, plus the raw inset a verbatim unit adds.
     *
     * @param previous the unit before, or {@code null} at a page's top
     * @param unit     the unit
     * @return the space in points
     */
    public static double gap(Unit previous, Unit unit) {
        double inset = unit.kind() == Kind.VERBATIM ? VERBATIM_INSET
                : unit.kind() == Kind.TABLE_ROW && (previous == null || previous.kind() != Kind.TABLE_ROW) ? CELL_INSET : 0;
        if (previous == null) {
            return inset;
        }
        boolean previousWeak = previous.spaceBelow() > 0;
        boolean unitWeak = unit.kind() == Kind.HEADING || unit.kind() == Kind.OTHER;
        double gap;
        if (previousWeak && unitWeak) {
            gap = Math.max(previous.spaceBelow(), unit.spaceAbove());
        } else if (previousWeak) {
            gap = previous.spaceBelow();
        } else {
            gap = unit.spaceAbove();
        }
        if (previous.kind() == Kind.VERBATIM) {
            gap += VERBATIM_INSET;
        }
        return gap + inset;
    }

    /** @return the pitch between a unit's baselines, in points */
    public static double pitch(Unit unit) {
        return unit.fontSize() * (unit.kind() == Kind.VERBATIM ? VERBATIM_PITCH : LayoutOracle.PITCH);
    }
}
