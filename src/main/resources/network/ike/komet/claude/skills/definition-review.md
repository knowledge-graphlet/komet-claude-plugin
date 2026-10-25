---
name: Definition review
description: Use when the person asks whether a concept's definition is right, complete or consistent with its peers, or why it classifies where it does.
category: Skill
---
Review a concept's logical definition against its peers and the knowledge base, and report what holds, what is missing and what looks wrong, each finding backed by what a tool returned.

## 1. Read it

- Resolve the concept with `concept`. If it is inactive, say so first: a retired concept's definition is history.
- Read its definition with `axioms`: the **stated** form (as authored) and the **inferred** form (after the last classification on this view).
- If the inferred form is empty, call `view_info`: the view may not be on the classification's path, or the concept may be primitive with nothing to infer. `debug_concept` tells which.

## 2. Read its peers

- Read the stated definitions of its parents and of two or three of its closest siblings (`children` of a parent) with `axioms`.
- Peers are the yardstick: the attributes they use, how they group them, and how specific their values are.

## 3. Check

- **Defined or primitive.** A stated sufficient set makes the concept defined (≡); without one it is primitive (⊑). Say which, and whether that suits it: a defined concept with the same defining attributes as a peer classifies as equivalent to it.
- **Supertypes.** Compare stated parents with inferred parents. A stated parent that is already an ancestor of another stated parent is redundant; an inferred parent no one stated shows what the definition implies.
- **Attributes.** An attribute the peers use and this concept lacks; a value more general or more specific than the peers'; a role ungrouped where the peers group it.
- **Values in range.** A value should sit in the hierarchy its attribute expects (a finding site in body structures, for example). Check with `is_a` against the expected top concept rather than from memory.
- **Retired references.** Any concept in the definition that `concept` reports inactive.

## 4. Report

- Lead with the verdict in a sentence, then one finding per point, each with the evidence: the tool's line, the peer it differs from, as Koncept Badges.
- Separate what the graph says from clinical judgement, and label the judgement.
- To show a corrected definition, draw it as a `koncept-definition` block, with `gap:` lines for what the knowledge base lacks.
- You cannot run the classifier. When the person asks you to make the corrections, load the Concept editing skill and write them as uncommitted changes for them to publish; propose a new concept only if they ask.
