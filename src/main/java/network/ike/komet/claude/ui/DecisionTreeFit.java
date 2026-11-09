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

/**
 * How a decision tree is fitted to the width its surface gives it. A tree drawn left to right
 * grows wider with every level. It is first drawn with narrower names, and what is still too wide
 * is scaled. Scaled below {@link #LEAST_SCALE} nothing in it can be read, so the tree is drawn as
 * an outline instead, a row for each node, which grows downward. When the surface widens far
 * enough the drawing left to right comes back.
 *
 * <p>Each time the drawing is given a width it reports the width it needs, and this answers
 * whether to draw it again, and how: the cap on its names, in ems, and its form. Nothing changes
 * twice at one width, so a redraw never asks for the drawing it replaced.
 */
final class DecisionTreeFit {

    /** The least a drawing left to right is scaled to; below this the tree is drawn as an outline. */
    static final double LEAST_SCALE = 0.7;

    private final double widest;
    private final double narrowest;
    private double ems;
    private boolean outline;
    /** The width the names were last fitted to; they grow back only when the region grows past it. */
    private double fittedAt = -1;
    /** The cap and the drawing's width before the last change of cap, from which the next is solved. */
    private double priorEms = -1;
    private double priorNatural = -1;
    /** The width the outline was last taken at; the drawing left to right is tried again only past it. */
    private double outlinedAt = -1;
    /** The least the drawing left to right needs with its narrowest names, as last measured or bounded. */
    private double acrossNatural = -1;

    /**
     * @param widest    the widest a name is drawn, in ems
     * @param narrowest the narrowest a name is drawn before the drawing is scaled, in ems
     */
    DecisionTreeFit(double widest, double narrowest) {
        this.widest = widest;
        this.narrowest = narrowest;
        this.ems = widest;
    }

    /** How wide a name may be in the drawing, in ems. */
    double ems() {
        return ems;
    }

    /** Whether the tree is drawn as an outline, a row for each node, and not left to right. */
    boolean outline() {
        return outline;
    }

    /**
     * The drawing was given a width.
     *
     * @param width   the width it was given
     * @param natural the width it needs as it is drawn now
     * @return whether to draw it again, at {@link #ems()} and in the form {@link #outline()} says
     */
    boolean given(double width, double natural) {
        if (width <= 0) {
            return false;
        }
        if (outline) {
            if (width > outlinedAt + 1 && width >= acrossNatural * LEAST_SCALE) {
                // the region has grown to where the drawing left to right may be readable: with its narrowest names first
                change(false, narrowest);
                return true;
            }
        } else if (natural > width + 1) {
            // were every part of the drawing a name, narrowing the names would narrow it this far and no further
            double least = ems > narrowest ? natural * narrowest / ems : natural;
            if (width < least * LEAST_SCALE) {
                acrossNatural = least;
                outlinedAt = width;
                change(true, widest);
                return true;
            }
        }
        double next = ems;
        if (natural > width + 1 && ems > narrowest) {
            next = Math.max(narrowest, Math.min(ems - 1, Math.floor(capFor(width, natural))));
        } else if (width > fittedAt + 1 && natural < 0.85 * width && ems < widest) {
            next = Math.min(widest, Math.max(ems, Math.floor(capFor(0.95 * width, natural))));
        }
        fittedAt = width;
        if (next != ems) {
            priorEms = ems;
            priorNatural = natural;
            ems = next;
            return true;
        }
        return false;
    }

    /** Takes a form at a cap; what was learned of the other form's widths says nothing of this one's. */
    private void change(boolean toOutline, double cap) {
        outline = toOutline;
        ems = cap;
        priorEms = -1;
        priorNatural = -1;
        fittedAt = -1;
    }

    /**
     * The cap at which the drawing would be a width: the drawing's width is about a fixed part
     * plus a part that grows with the cap, which two drawings at different caps solve for;
     * with one, the width is taken to grow with the cap alone.
     */
    private double capFor(double width, double natural) {
        if (priorEms > 0 && priorEms != ems && priorNatural != natural) {
            double perEm = (priorNatural - natural) / (priorEms - ems);
            if (perEm > 0) {
                return ems + (width - natural) / perEm;
            }
        }
        return ems * width / natural;
    }
}
