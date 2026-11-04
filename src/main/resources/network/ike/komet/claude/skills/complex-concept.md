---
name: Complex concept
description: Use when a decision point, a procedure in a specific form, a panel, or any phenomenon the knowledge base lacks must be stated as a complex concept — a concept with a description logic definition and an expansion, focused on one phenomenon with prerequisites or uniform over members — so a decision tree, a statement or a measure can name one concept for it.
category: Skill
---
State a complex concept as a fenced `complex-concept` block and let Komet draw it: the concept, what it is a kind of, and its expansion, with what the specification still lacks listed beneath. Never draw it as a table or a list, and never write a composed expression into a statement: the point of a complex concept is that instance data names one concept (`arch-anf-precoordination`).

## With a paired document

When this conversation has a paired document, the block goes into the document, not your reply: insert it with `propose_block_insert` (kind `listing`, the text starting with the style line `[complex-concept]`), or change it with `propose_block_edit`, and answer in sentences saying what you added or changed, and why. Never restate the block in the reply. The tool's result ends with what Komet found in the document's structured blocks; correct those in this turn. With no paired document, state the block in your reply as below; if the person wants the decision kept, offer `create_document`.


## A label is the fully qualified name

The label in `k:Key[label]` is the concept's fully qualified name: it must stand alone, out of this tree and this document. Write `Clear magnetic resonance angiography result`, not `clear`. The same name anywhere is the same concept, here and in every other document, so two nodes that mean the same thing share one name, and two that mean different things must not. A concept the knowledge base already holds under that exact name is that concept. Komet fits long names to the drawing, so never shorten one for the page. If a tool says a name already has a different tree or specification, ask the person whether they are the same; if not, write a more specific name.


## The block

One line per vertex, two spaces of indentation per level; the concept tokens are the decision-tree block's, `k:Key[label] @id=<identity>`.

```complex-concept
concept: k:SurveillanceDecision[Surveillance imaging decision after aneurysm seal] @id=rda-surveillance-decision
  expansion: focused k:ImagingDecision[Surveillance imaging decision] @id=rda-imaging-decision
    prerequisite: k:AneurysmSealed[Intracranial aneurysm sealed by endovascular treatment] @id=rda-aneurysm-sealed
      value = [1,1] k:Presence[presence] @id=rda-presence
    prerequisite: k:TimeSinceSeal[Time since the seal] @id=rda-time-since-seal
      value = [2,2] k:Year[year] @id=rda-year
    prerequisite: k:AneurysmSymptoms[Symptoms attributable to the aneurysm] @id=rda-aneurysm-symptoms
      value = [0,0] k:Presence[presence] @id=rda-presence
```

```complex-concept
concept: k:Perrla[PERRLA panel] @id=perrla
  parent: k:AssessmentPanel[Clinical assessment panel] @id=assessment-panel
  expansion: uniform and k:Presence[presence] @id=presence
    member: k:PupilEquality[Pupil equality assessment] @id=pupil-equality
    member: k:PupilRoundness[Pupil roundness assessment] @id=pupil-roundness
    member: k:LightReactivity[Light reactivity assessment] @id=light-reactivity
    member: k:Accommodation[Accommodation assessment] @id=accommodation
```

### Lines

- `concept:` the root, the complex concept itself; its label is its name.
- `parent:` what it is a kind of. The description logic definition is derived from it, as the architecture prescribes: a primitive subtype of the parent, with no roles. A focused concept's parent defaults to its focus; a uniform concept states its grouper parent.
- `expansion: focused <token>` the phenomenon a provided value applies to, with `prerequisite:` lines beneath, each carrying one `value =` line: the fixed, definitional value on the unit it names. Presence is `[1,1]` and absence `[0,0]` on the presence unit; a quantity is an interval on its unit, `[5,inf)` for five minutes or more. A prerequisite relates to the focus as a `precondition`, the one association there is; `association=precondition` may be written and is assumed.
- `expansion: uniform and|or <domain token>` the conjunction and the measurement domain every member shares, with `member:` lines beneath. An OR conjunction says how a present value is handled, `unsafe=refuse`, `unsafe=flag` or `unsafe=retain`; an AND conjunction distributes any value safely and carries none.

### What never goes in

- **Absence.** No concept means "no scan" or "not done". Absence is a result on the presence scale inside a statement, never a topic. A decision tree's null course holds no concept at all.
- **Valuations.** A prerequisite's value is what makes the concept that concept. A cost, a probability, a count or a price of a healthy year belongs on the decision tree that names the concept, never in its expansion.
- **Instance facts.** No subject, author or time: the prerequisites are definitional, true of every instance of the concept.

### Tokens and identities

As in the decision-tree block: a concept the knowledge base holds carries the exact identifier a tool returned; one it lacks carries a short symbolic identity of your own, the same symbol wherever it recurs in the answer, and Komet reads it as a concept local to the document. The complex concept itself is usually new, so its `@id` is a symbol. A decision tree in the same answer names the complex concept by that symbol, and its chip then says what the concept is.

## Answering

Put the block first, then a sentence or two: what the concept is for, and which of its prerequisites are grounded. Komet draws the card as the complex concept editor, so the person edits a value, a topic or an association in place and adds a prerequisite from the clause's menu, and **Write back to the block** restates the block in the document; a decision tree's chip that names the concept offers **Edit complex concept**, which peeks at the same card. Komet lists what the specification still lacks; do not restate the block to fix it unless asked. When the person wants the concept in the knowledge base, tell them the card offers **Propose**, which writes the concept and its specification as one uncommitted change, and that every local symbol becomes a concept of its own under a derived identity.
