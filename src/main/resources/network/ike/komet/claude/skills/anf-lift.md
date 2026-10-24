---
name: ANF lift
description: Use when the person asks to turn clinical narrative into Analysis Normal Form (ANF) statements, or to check or explain a statement's ANF form.
category: Skill
---
Lift clinical narrative into Analysis Normal Form: structured clinical statements whose every concept is grounded in the knowledge base. In this card you write the statements for the person to read and copy; they are not saved. For a lift whose statements are kept with a history, the person opens **ANF Lift** from the journal's **+** menu.

## Grounding

- Never state a concept, code, name or unit from memory. `search` it, confirm it with `concept`, and use only the identifier the tool returned.
- A meaning the knowledge base lacks is a **candidate**; an ambiguity in the narrative is a **clarify**. An honest gap is right; an invented code is the worst thing you can do here.

## Each statement

- **Type**: `performance` (something done or observed), `request` (something asked for or ordered) or `narrative` (neither fits).
- **Topic**: exactly one concept, with no polarity, subject, context or time in it.
- **Subject of information**: who or what the statement is about when that is not the patient (a family member, a fetus, a donor); left out otherwise. There is no "family history of X" or "fetal X" topic.
- **Circumstance**, one kind only:
  - performance: status, result, health_risk, normal_range, body_site, method, laterality;
  - request: priority, requested_result, repetition, conditional_trigger, method;
  - narrative: text;
  - any kind: timing, purpose.
- **Results and measures**: an interval with its measure semantic. A number without a measure semantic is not a result.
- **Presence and absence** are results on the presence scale, never an "absent" concept: present `[1,inf)`, absent `[0,0]`, unknown `[0,inf)`, with the measure semantic *Presence (property) (qualifier value)*, found by `search`. A finding stated with no value is a presence result.

## Method

1. Split the narrative into facts; one statement per fact. Bilateral findings split by laterality.
2. Decide each fact's subject.
3. Classify it: performance, request or narrative.
4. Choose and ground its topic.
5. Ground its result, then its other fields.
6. A prerequisite or reason becomes a statement of its own.
7. A field you cannot ground becomes a candidate or a clarify. Never drop a statement because part of it will not ground.

## Writing the statements

Write each statement as an `[anf]` block in a fenced `asciidoc` code block, one field per line:

```asciidoc
[anf,type=performance]
--
topic:: k:Topic[label] @id=<identifier a tool returned>
subject_of_information:: k:Subject[label] @id=<identifier>
result:: [1,inf) k:Presence[Presence] @id=<identifier>
--
```

- A grounded field is `k:<Key>[<label>] @id=<identifier>`: the key a short CamelCase name, the label the concept's name, the identifier exactly as a tool returned it.
- An interval is written `[lo,hi]`, with `(` or `)` for an open end and `inf` or `-inf` for no bound, before the measure semantic.
- A candidate is `candidate[<label>] disp=PENDING near=<id>;<id> text="<the narrative's words>"`, naming the nearest concepts you found.
- A clarify is `clarify[<field>] "<the question to ask>"`.
- `purpose::` and `conditional_trigger::` may repeat; narrative `text::` is quoted.

After the blocks, list each candidate and clarify in a sentence so the person sees what is left to settle.
