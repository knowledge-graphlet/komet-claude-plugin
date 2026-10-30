---
name: Semantic authoring
description: Use when the person asks to create, author or propose a new semantic (a record that is an instance of a pattern on a concept, a pattern or another semantic), or to withdraw a semantic proposal already open.
category: Skill
---
Author a semantic the way a careful terminologist would: read the pattern it instantiates, make sure every component it names exists and is published, value every field, and hand it to the person as a proposal they review and publish. You never create a semantic yourself; you propose one.

## 1. Understand what the semantic is made of

A semantic is:

- a **pattern**: the record type whose fields the semantic fills;
- a **reference component**: the concept, pattern or semantic the record is about;
- one **value per field** of the pattern, in the pattern's field order.

The person usually names these in prose ("a Regulatory Classification semantic on the Xpert Xpress device, with CLIA complexity Waived, prescription status Prescription Required and device class Class II"). Find the pattern with `search` and read it with `pattern`: its fields, in order, each with its meaning, purpose and data type, are the shape the semantic must take. Restate what you understood as a short table of pattern, reference component and each field's value before anything else.

## 2. Value every field

- A **component** field takes one existing component of the kind the data type allows (a concept for a concept field; any component for a component field).
- A **component set or list** field takes one or more existing components.
- A **string**, **integer**, **float** or **boolean** field takes a literal of that type (a detection limit of 403, say).
- A semantic has a value for every field. A field the request does not speak to is a question for the person, never a guess and never left out. Ask about every such field in one go.
- Every identifier you use is one a tool returned in this conversation. Never one you recall.

## 3. Make sure every component exists

For the reference component and each component a field names:

- `search` the name and its likely synonyms; read each promising hit with `concept` (or `semantic_info` for a semantic).
- An active component that means the same thing is reused: show it as a Koncept Badge and confirm it is what they mean.
- A concept that does not exist is proposed first, with the **Concept authoring** skill: place it under the nearest parent (say which, and ask when the person has not said), and propose it with `propose_concept`. Propose every missing concept before proposing the semantic.

## 4. Have the concepts published first

Once the missing concepts are proposed, stop and tell the person: the concepts wait in the journal's **Unpublished Changes** panel, where they review and **Publish** them; the semantic comes once they are published, and you will write it when they say to proceed. Do not propose the semantic in the same turn.

If they ask you to write the semantic while its pattern or a component it names is still unpublished, say that a semantic published before them would point at nothing, and only then pass `allow_unpublished: true`.

## 5. Show the semantic, then propose it when asked

Show the full proposal: the pattern, the reference component, and each field in the pattern's order with its value, every component as a Koncept Badge. Only when the person asks to create, author or proceed, call `propose_semantic` with:

- `pattern`: the pattern's UUID;
- `reference_component`: the component's identifier;
- `fields`: one value per field, in the pattern's order: an identifier for a component field, an array of identifiers for a set or list field, a literal for the rest;
- `editorial_note`: what the semantic records and where its values came from.

Several semantics on the same component (a Regulatory Classification and a Test Performed record on one device, say) are separate proposals, one call each. A semantic on another semantic (an Allowed Results record on a Test Performed record) takes that semantic's UUID as its reference component; the semantic it is on must be published first, or the person must say to go ahead.

Then tell them: the proposal is open, uncommitted, in the journal's Unpublished Changes panel under the component it is on; nothing exists until they press **Publish** there. Keep the proposal id the tool returns.

## 6. Withdraw it

To change a proposal, `discard_semantic_proposal` with its id, then `propose_semantic` again with the corrected values, only when the person asks. A published proposal is a committed semantic; the tool leaves it alone.
