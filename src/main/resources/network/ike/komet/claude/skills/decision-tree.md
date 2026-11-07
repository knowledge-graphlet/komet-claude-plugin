---
name: Decision tree
description: Use when the person asks to draw, state, read, compare or roll back a decision tree, a decision analysis, or the expected cost or value of alternatives under uncertainty: a decision among alternatives, with chances, events and outcomes that carry probabilities and money or health costs.
category: Skill
---
State a decision as three fenced blocks and let Komet draw it. The **model** is a `graphlet-model` block: the tree's structure and its parameters together, one versioned property graph, named by `id=`. The **renderings** are a `decision-tree` block and a `tornado` block that each name the model with `model=<id>` and carry nothing but how it is viewed: the tree in decision-analytic notation, every node's concept as a chip, the value each node is left with after rollback under the valuation coordinate; and the one-way sensitivity, one bar per value. **Never roll the tree back yourself and never draw it as box art or a nested list**: Komet folds expected cost at a chance, least cost at a decision, and prices health into money at the health price the rendering's info sets (`health-price=100000`, in the money semantic's units; the reader can move it). Add `cohort=1000` to the tree rendering's info and Komet also counts the tree out for a thousand: beneath every branch how many go that way, and beneath the drawing where the thousand who take each alternative end up, which reads without any price; beneath the drawing it also says what the choice trades, money and health kept apart, and the price of a healthy year at which the alternatives tie.

A number someone determined, a chance or a cost, is an ANF statement of the document, and the model's parameter reads it by name (see *Parameters that read statements*). Values are in the model, views on the renderings: a reader who tries a value in the tornado writes it into the model, and every rendering redraws.

## With a paired document

When this conversation has a paired document, the blocks go into the document, not your reply: insert each with `propose_block_insert` (kind `listing`, the text starting with the style line, `[graphlet-model, id=bimo-site-model]` for the model, `[decision-tree, model=bimo-site-model, cohort=100]` and `[tornado, model=bimo-site-model]` for the renderings), or change one with `propose_block_edit`, and answer in sentences saying what you added or changed, and why. Never restate a block in the reply. The tool's result ends with what Komet found in the document's structured blocks; correct those in this turn. With no paired document, state the blocks in your reply as below; if the person wants the decision kept, offer `create_document`.


## A label is the fully qualified name

The label in `k:Key[label]` is the concept's fully qualified name: it must stand alone, out of this tree and this document. Write `Clear magnetic resonance angiography result`, not `clear`. The same name anywhere is the same concept, here and in every other document, so two nodes that mean the same thing share one name, and two that mean different things must not. A concept the knowledge base already holds under that exact name is that concept. Komet fits long names to the drawing, so never shorten one for the page. If a tool says a name already has a different tree or specification, ask the person whether they are the same; if not, write a more specific name.


## One rule for every part

A tree has five parts: decision, alternative, chance, event and outcome. A `residual:` is an event. Each has text, its concept's name, and may have an encoding, a complex concept. A decision and a chance read as questions, an alternative as a course of action, an event as an answer, and an outcome as an end state.

A chance is one topic on one scale, and each event is one point on that scale. Write `Inspection result`, not `Site data`, and give it the events that cover the scale: fail, pass with conditions, pass, each named so it stands alone. A result is never a code: ANF states a quantity on a scale, and the scale's meaning is knowledge. Presence is a scale of two points.

Narrative stays in the report. Discrete findings are statements lifted from it, and the tree takes the overall result. Give a finding its own chance only when it alone changes the decision, because every finding multiplies the paths.

## The blocks

The model: one line per vertex or value, two spaces of indentation per level; indentation carries the edges. The model's info names it, `id=rda-scan-model`; it carries no price and no cohort, which are the renderings'.

```graphlet-model id=rda-scan-model
decision: k:ScanNow[Scan now?] @id=rda-scan-now
  parameter = [0.02,0.02] k:Reopening[Late reopening hazard in year 2] @id=rda-reopening-hazard basis=estimated
  parameter = [0.9,0.9] k:Sensitivity[MRA sensitivity] @id=rda-mra-sensitivity basis=estimated
  parameter = k:FalseNegative[MRA false-negative rate] @id=rda-mra-false-negative from k:Complement[] of k:Sensitivity[] @id=rda-mra-sensitivity
  parameter = [150000,150000] k:RuptureCost[Cost of a rupture] @id=rda-rupture-cost in k:Usd[US dollar] @id=rda-usd basis=estimated source=johnston2008
  alternative: k:Scan[Magnetic resonance angiography screening] @id=rda-scan
    money = [700,700] k:Usd[US dollar] @id=rda-usd basis=estimated
    chance: k:MraResult[MRA result] @id=rda-mra-result
      event: k:Positive[Positive magnetic resonance angiography result] @id=rda-mra-positive
        p = from k:BayesMarginal[Bayes marginal] of k:Reopening[] @id=rda-reopening-hazard k:Sensitivity[] @id=rda-mra-sensitivity k:FalsePositive[] @id=rda-mra-false-positive
        outcome: k:Treated[Treated intracranial aneurysm] @id=rda-treated
          money = [35000,35000] k:Usd[US dollar] @id=rda-usd basis=estimated
      residual: k:Negative[Negative magnetic resonance angiography result] @id=rda-mra-negative
        outcome: k:Clear[No intracranial aneurysm found] @id=rda-clear
  alternative: k:NoScan[No magnetic resonance angiography screening] @id=rda-no-scan
    outcome: k:Untreated[Untreated intracranial aneurysm rupture risk] @id=rda-untreated
      money = from k:Product[] of k:RuptureRisk20[] @id=rda-rupture-risk-20 k:RuptureCost[] @id=rda-rupture-cost in k:Usd[US dollar] @id=rda-usd
```

The renderings, each naming the model and nothing more. Their bodies are empty: a rendering holds no structure and no number.

```decision-tree model=rda-scan-model health-price=100000 cohort=1000
```

```tornado model=rda-scan-model health-price=100000
```

Two renderings of one model under different views, another health price or another cohort, are two `decision-tree` blocks naming the same model. Two sets of numbers are two models.

### Vertices, `word: k:Key[label] @id=<identity>`

- `decision:` the root, and any later decision; `alternative:` a choice beneath a decision.
- `chance:` an uncertain fork; `event:` one of its branches, carrying a `p =` line; `residual:` the branch that takes whatever probability is left, carrying no `p =` line. One residual per chance.
- `outcome:` a leaf. An outcome that is an event with consequences of its own, a rupture say, carries `tree=<identity>` naming the root of another tree block that states those consequences once (see *Linked trees*). A node may carry `statement=<identity>;<identity>` naming the ANF statements that say what it is (see *Statements on the tree*).

### Values, `word = ...`

- `p = [lo,hi]` a probability on an event; `money = [lo,hi] k:Unit[label] @id=<identity>` a cost in a currency; `health = [lo,hi] k:Unit[label] @id=<identity>` a cost in healthy life (QALYs), priced into money by the health price. A cost line sits under the node that pays it; a benefit is a negative cost. **The token after a cost's interval is its unit, one currency concept for every money line and one QALY concept for every health line** (`k:Usd[US dollar] @id=rda-usd`, `k:Qaly[QALY] @id=rda-qaly`); it is never a quantity. A node carries at most one money line and one health line; a risk that may or may not happen is a chance beneath the node, with the cost on its event, not an expected cost on the node.
- An interval is `[lo,hi]`, a point `[x,x]`, an open end `(` or `)`, no bound `inf`; the renderer carries the spread through rollback.
- `basis=estimated|cited|derived` says where a number comes from; `source=<identity>` names the citation.
- `parameter = [lo,hi] k:Quantity[label] @id=<identity> ...` states a model assumption once, under the root; any value may then be `from <kind> of <quantities>`: kinds are `Complement` (1 − x), `Product`, `CumulativeHazard` (1 − (1 − h)^n), `BayesPosterior` (prior, sensitivity, false-positive rate) and `BayesMarginal` (the same three; the share that tests positive). A derived value has no interval of its own. A value that is simply one of the parameters is written `from` that quantity alone, with nothing after it: `p = from k:Reopening[] @id=rda-reopening-hazard` — never restate the number by hand where a parameter already states it.
- `parameter = [v,v] anf:<name>` reads a parameter from one of the document's ANF statements (see *Parameters that read statements*); a value then reads it the same way, `p = from anf:<name>` or `money = from anf:<name>`, with nothing after it.
- **A derived parameter names its quantity first, then the derivation**: `parameter = k:Risk[Twenty-year rupture risk] @id=rda-risk from CumulativeHazard of k:Hazard[] @id=rda-hazard k:Years[] @id=rda-years`. The quantity never comes after the operands.

A third cost kind counts a consequence in its own unit and is never priced: `count = [1,1] k:Angiogram[angiograms] @id=rda-angiogram` on the branch that brings it, or `count = from k:RuptureRisk20[] @id=rda-rupture-risk-20 in k:Rupture[ruptures] @id=rda-rupture` where a parameter already carries the chance; the concept after `in` names what is counted, and like every token it carries an `@id`. **One counted thing is one concept wherever it recurs**: the ruptures counted on three branches all name `@id=rda-rupture`, so the ledger has one ruptures column; a fresh symbol per branch would make a column each. Komet keeps every count beside money and health in a ledger beneath the drawing, per cohort when one is counted, and says who spares how many at what cost each, so a tree that prices nothing, as a regulator's often does, still weighs its alternatives; such a tree leaves its choice open rather than choosing on nothing.

### Tokens and identities

Inside this block a concept is always `k:Key[label] @id=<identity>`: a short CamelCase key, the label in brackets, and the identity after `@id=`. **This is not the Koncept Badge form** used in prose; never write `k:uuid=` or `k:sctid=` inside the block.

- A concept the knowledge base holds carries the **exact identifier a tool returned** (`search`, `concept`) as its identity: `k:Aneurysm[Intracranial aneurysm] @id=<the UUID the tool returned>`. It then draws as the shared Koncept chip.
- A concept the knowledge base lacks (a quantity, a currency, a situation the model names) carries a short symbolic identity of your own, `@id=rda-usd`, the same symbol wherever it recurs; Komet reads it as a concept local to the block, draws it as a dashed chip, and lists the symbols beneath the tree. Never invent a store identifier for one.
- A decision point, an alternative or an event that the knowledge base lacks may be stated as a complex concept in a `complex-concept` block in the same answer (the `complex-concept` skill): the tree's token then carries that block's symbol as its `@id`, so the chip on the tree says what the concept is, and the decision names the patient it is for through the block's prerequisites.
- A derivation kind is written as its bare name after `from` (`from CumulativeHazard of ...`) or as a token with no identity (`from k:CumulativeHazard[] of ...`); its operands after `of` are the quantities' tokens, each with the quantity's `@id=`.

### Parameters that read statements

A chance or a cost that someone determined is a performance statement of the document, stated in an `anf` block **before** the model and named for it, as a node's statements are. The statement is the record of the determination: its `topic` is what the number is about, the event or the procedure the tree already names; its `result` is the range the person or the study determined, as an interval, or a point when they stated one, on the number's scale; its `subject_of_information` is the group the number is about, which for a share is what it is a fraction of; its `method` is how it was determined, estimated, elicited or counted. A share's scale is `candidate[share of the subject group] disp=PENDING`: the members with the topic over the members of the group, on zero to one; the group itself is the subject of information, never part of the scale. An odds ratio, a relative risk reduction or a yearly hazard is a scale of its own, not a share. A money scale is the currency, `candidate[US dollar] disp=PENDING` where the knowledge base holds none.

```anf type=performance id=est-material-problem
topic:: candidate[material problem in the site's clinical data] disp=PENDING
subject_of_information:: candidate[Site inspection decision for a first-of-a-kind submission] disp=PENDING
result:: [0.005,0.4] candidate[share of the subject group] disp=PENDING
method:: k:Estimated[Estimated basis] @id=96420d3b-09c4-5ebc-a292-7d0a0423a6de
```

**Write a statement for every number you extracted or estimated about a named group**; a bare value on a model row, `parameter = [lo,hi] k:Quantity[…]`, is for a number nothing stands behind, and the row says so for the person to replace. A number you propose names you as the performer, `participant:: candidate[Komet assistant] disp=PENDING role=candidate[performer] disp=PENDING`; a number you extract from a source names the source's author as the performer and carries `source=<id>` on the line, where the id is a footnote or bibliography entry the document already defines. Nothing you write is confirmed by you: it is uncommitted until the person commits the document, as a concept edit is, and so is a value you set on a model row.

The model's parameter then names the statement, and holds the value the tree runs at as an ordinary measure with equal bounds on the statement's own scale: `parameter = [0.15,0.15] anf:est-material-problem`. The value is the model's choice, within the range the statement determined; leave it out, `parameter = anf:est-material-problem`, and the tree runs at the midpoint of the range, which Komet computes and never writes. A read of that parameter is `p = from anf:est-material-problem` or `money = from anf:est-inspection-cost`, with no unit after it: the scale is the statement's. The tornado sweeps such a parameter across the statement's range, as stated, so a prior believed to lie between 0.5% and 40% shows where the choice turns. A parameter nobody determined, a sketch's number, stays a bare `parameter = [lo,hi] k:Quantity[…]` line.

### Statements on the tree

When the person asks for the tree's courses and findings in ANF, state each as an `anf` fenced block **before** the tree, naming it for the document, and let the node hold it by that name with `statement=anf:<name>`:

```anf type=request id=req-scan
topic:: k:MraHead[Magnetic resonance angiography of head without contrast] @id=437731000119100
timing:: [2,2] k:Year[year] @id=258707000
purpose:: candidate[detection of reopening of the sealed aneurysm] disp=PENDING
```

```anf type=performance id=perf-mra-positive
topic:: candidate[reopening of the sealed aneurysm] disp=PENDING
result:: [1,1] k:Presence[presence] @id=705057003
method:: k:MraHead[Magnetic resonance angiography of head without contrast] @id=437731000119100
```

and in the tree `alternative: k:Scan[Magnetic resonance angiography screening] @id=437731000119100 statement=anf:req-scan`, `event: k:Positive[Positive magnetic resonance angiography result] @id=rda-mra-positive statement=anf:perf-mra-positive`.

- **A decision holds the situation it is made in**: when the decision's concept is a complex concept, its prerequisites are that situation and nothing more is written; otherwise the decision holds performance statements of the patient's state. **An alternative holds a request** (`type=request`): what is asked for, its `timing::`, its `purpose::`, a `requested_result::` when one is wanted. **Every event holds the situation it reveals** as a performance (`type=performance`): what is found or is so, its `result::` on the presence scale `[1,1]` present, `[0,0]` absent, or a measure, and its `method::` when a test reveals it; a residual event is a situation too ("no reopening" is the reopening, `[0,0]`). **An outcome holds the situation the course ends in**, an endpoint such as a rupture, a death or a recovery. A chance holds none; its events do. **The null course holds none**: "no scan" is not a statement of anything, never a negated topic.
- The statements are templates: no `subject_of_record::`, `author::` or `statement_time::`; the tree is the note.
- Every field but `text::` takes a concept or a measure: `disposition::`, `method::`, `purpose::` and the like a `k:` token or a candidate, `result::`, `timing::` and the like an interval then its unit. **Leave out a field you have no concept for**; never write a bare word such as `disposition:: performed`.
- Inside an `anf` block a concept is a grounded `k:Key[label] @id=<identity>` the knowledge base holds, a SNOMED CT identifier or the identifier a tool returned, or **`candidate[label] disp=PENDING`** when it lacks one. Never a block-local symbol such as `@id=rda-reopening`: those belong to the tree and complex-concept blocks only.
- Komet draws an ANF mark beside each node that holds a statement: resting on it shows the statement, a click opens it editable. A statement of the wrong type, or on a decision or chance, comes back as a finding.

### Linked trees

Consequences that recur, what a rupture leads to say, are written **once** as their own model whose root is a chance with no choice in it, and folded in at every outcome that ends in that event with `tree=<identity>` of that root:

```graphlet-model id=rda-rupture-model
chance: k:RuptureConsequences[Consequences of a rupture] @id=rda-rupture-consequences
  parameter = [0.35,0.35] k:CaseFatality[Case fatality of a rupture] @id=rda-case-fatality basis=estimated
  event: k:Death[death] @id=419620001 statement=anf:perf-death
    p = from k:CaseFatality[] @id=rda-case-fatality
    health = [20,20] k:Qaly[QALY] @id=rda-qaly basis=estimated
    count = [1,1] k:Deaths[deaths] @id=rda-deaths
    outcome: k:Dead[death] @id=rda-dead
  residual: k:Survival[survival] @id=rda-survival
    ...
```

and in the main model `outcome: k:Ruptured[rupture] @id=rda-ruptured tree=rda-rupture-consequences`, with a `decision-tree` rendering of each model. The outcome then costs what the linked tree costs from its root, at the same price; its counts join the ledger and its leaves the tally. The linked tree's parameters live in its own model, its costs on the same money and health units. Komet links the two both ways, so the reader can go from the outcome to the consequences and back. A tree never folds itself in.

## Answering

Put the statements, the model and its two renderings first, then two or three sentences: which concepts are local, and which assumptions the comparison is most sensitive to. **Do not compute, estimate or quote expected values, totals or the favoured alternative**: Komet rolls the tree back and shows every node's value and the chosen alternative beside it, and a number you work out in prose will disagree with the rendered one. If the person gives numbers, state them as parameters with `basis=cited` or `basis=estimated` as they told you; if they give none, say which you assumed and mark them `basis=estimated`.

When Komet reads your model and sends its findings back, restate your whole answer with the model corrected: change only what the findings name, keep every identity and every number the person gave, and do not explain the correction. A node Komet reports as not determinable lacks a value it can fold: give it the cost line, probability or parameter the finding names.
