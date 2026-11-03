---
name: Decision tree
description: Use when the person asks to draw, state, read, compare or roll back a decision tree, a decision analysis, or the expected cost or value of alternatives under uncertainty: a decision among alternatives, with chances, events and outcomes that carry probabilities and money or health costs.
category: Skill
---
State a decision tree as a fenced `decision-tree` block and let Komet draw it: the tree in decision-analytic notation, every node's concept as a chip, and the value each node is left with after rollback under the valuation coordinate. **Never roll the tree back yourself and never draw it as box art or a nested list**: the renderer folds expected cost at a chance, least cost at a decision, and prices health into money at the health price the block's info sets (`health-price=100000`, in the money semantic's units; the reader can move it). Add `cohort=1000` to the info and Komet also counts the tree out for a thousand: beneath every branch how many go that way, and beneath the drawing where the thousand who take each alternative end up, which reads without any price; beneath the drawing it also says what the choice trades, money and health kept apart, and the price of a healthy year at which the alternatives tie.

## The block

One line per vertex or value, two spaces of indentation per level; indentation carries the edges.

```decision-tree health-price=100000 cohort=1000
decision: k:ScanNow[Scan now?] @id=rda-scan-now
  parameter = [0.02,0.02] k:Reopening[Late reopening hazard in year 2] @id=rda-reopening-hazard basis=estimated
  parameter = [0.9,0.9] k:Sensitivity[MRA sensitivity] @id=rda-mra-sensitivity basis=estimated
  parameter = k:FalseNegative[MRA false-negative rate] @id=rda-mra-false-negative from k:Complement[] of k:Sensitivity[] @id=rda-mra-sensitivity
  parameter = [150000,150000] k:RuptureCost[Cost of a rupture] @id=rda-rupture-cost in k:Usd[US dollar] @id=rda-usd basis=estimated source=johnston2008
  alternative: k:Scan[scan] @id=rda-scan
    money = [700,700] k:Usd[US dollar] @id=rda-usd basis=estimated
    chance: k:MraResult[MRA result] @id=rda-mra-result
      event: k:Positive[positive] @id=rda-mra-positive
        p = from k:BayesMarginal[Bayes marginal] of k:Reopening[] @id=rda-reopening-hazard k:Sensitivity[] @id=rda-mra-sensitivity k:FalsePositive[] @id=rda-mra-false-positive
        outcome: k:Treated[treated] @id=rda-treated
          money = [35000,35000] k:Usd[US dollar] @id=rda-usd basis=estimated
      residual: k:Negative[negative] @id=rda-mra-negative
        outcome: k:Clear[clear] @id=rda-clear
  alternative: k:NoScan[no scan] @id=rda-no-scan
    outcome: k:Untreated[rupture risk] @id=rda-untreated
      money = from k:Product[] of k:RuptureRisk20[] @id=rda-rupture-risk-20 k:RuptureCost[] @id=rda-rupture-cost in k:Usd[US dollar] @id=rda-usd
```

### Vertices, `word: k:Key[label] @id=<identity>`

- `decision:` the root, and any later decision; `alternative:` a choice beneath a decision.
- `chance:` an uncertain fork; `event:` one of its branches, carrying a `p =` line; `residual:` the branch that takes whatever probability is left, carrying no `p =` line. One residual per chance.
- `outcome:` a leaf. A node may carry `statement=<identity>;<identity>` naming the ANF statements that say what it is (see *Statements on the tree*).

### Values, `word = ...`

- `p = [lo,hi]` a probability on an event; `money = [lo,hi] k:Unit[label] @id=<identity>` a cost in a currency; `health = [lo,hi] k:Unit[label] @id=<identity>` a cost in healthy life (QALYs), priced into money by the health price. A cost line sits under the node that pays it; a benefit is a negative cost. **The token after a cost's interval is its unit, one currency concept for every money line and one QALY concept for every health line** (`k:Usd[US dollar] @id=rda-usd`, `k:Qaly[QALY] @id=rda-qaly`); it is never a quantity. A node carries at most one money line and one health line; a risk that may or may not happen is a chance beneath the node, with the cost on its event, not an expected cost on the node.
- An interval is `[lo,hi]`, a point `[x,x]`, an open end `(` or `)`, no bound `inf`; the renderer carries the spread through rollback.
- `basis=estimated|cited|derived` says where a number comes from; `source=<identity>` names the citation.
- `parameter = [lo,hi] k:Quantity[label] @id=<identity> ...` states a model assumption once, under the root; any value may then be `from <kind> of <quantities>`: kinds are `Complement` (1 − x), `Product`, `CumulativeHazard` (1 − (1 − h)^n), `BayesPosterior` (prior, sensitivity, false-positive rate) and `BayesMarginal` (the same three; the share that tests positive). A derived value has no interval of its own. A value that is simply one of the parameters is written `from` that quantity alone, with nothing after it: `p = from k:Reopening[] @id=rda-reopening-hazard` — never restate the number by hand where a parameter already states it.
- **A derived parameter names its quantity first, then the derivation**: `parameter = k:Risk[Twenty-year rupture risk] @id=rda-risk from CumulativeHazard of k:Hazard[] @id=rda-hazard k:Years[] @id=rda-years`. The quantity never comes after the operands.

A third cost kind counts a consequence in its own unit and is never priced: `count = [1,1] k:Angiogram[angiograms] @id=rda-angiogram` on the branch that brings it, or `count = from k:RuptureRisk20[] @id=rda-rupture-risk-20 in k:Rupture[ruptures] @id=rda-rupture` where a parameter already carries the chance; the concept after `in` names what is counted, and like every token it carries an `@id`. **One counted thing is one concept wherever it recurs**: the ruptures counted on three branches all name `@id=rda-rupture`, so the ledger has one ruptures column; a fresh symbol per branch would make a column each. Komet keeps every count beside money and health in a ledger beneath the drawing, per cohort when one is counted, and says who spares how many at what cost each, so a tree that prices nothing, as a regulator's often does, still weighs its alternatives; such a tree leaves its choice open rather than choosing on nothing.

### Tokens and identities

Inside this block a concept is always `k:Key[label] @id=<identity>`: a short CamelCase key, the label in brackets, and the identity after `@id=`. **This is not the Koncept Badge form** used in prose; never write `k:uuid=` or `k:sctid=` inside the block.

- A concept the knowledge base holds carries the **exact identifier a tool returned** (`search`, `concept`) as its identity: `k:Aneurysm[Intracranial aneurysm] @id=<the UUID the tool returned>`. It then draws as the shared Koncept chip.
- A concept the knowledge base lacks (a quantity, a currency, a situation the model names) carries a short symbolic identity of your own, `@id=rda-usd`, the same symbol wherever it recurs; Komet reads it as a concept local to the block, draws it as a dashed chip, and lists the symbols beneath the tree. Never invent a store identifier for one.
- A decision point, an alternative or an event that the knowledge base lacks may be stated as a complex concept in a `complex-concept` block in the same answer (the `complex-concept` skill): the tree's token then carries that block's symbol as its `@id`, so the chip on the tree says what the concept is, and the decision names the patient it is for through the block's prerequisites.
- A derivation kind is written as its bare name after `from` (`from CumulativeHazard of ...`) or as a token with no identity (`from k:CumulativeHazard[] of ...`); its operands after `of` are the quantities' tokens, each with the quantity's `@id=`.

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

and in the tree `alternative: k:Scan[scan] @id=437731000119100 statement=anf:req-scan`, `event: k:Positive[positive] @id=rda-mra-positive statement=anf:perf-mra-positive`.

- **An alternative holds a request** (`type=request`): what is asked for, its `timing::`, its `purpose::`, a `requested_result::` when one is wanted. **An event or outcome holds a performance** (`type=performance`): what is found or happens, its `result::` on the presence scale `[1,1]`, `[0,0]`, or a measure, its `method::`. A decision and a chance hold none; the alternatives and events beneath them do. **The null course holds none**: "no scan" is not a statement of anything, never a negated topic.
- The statements are templates: no `subject_of_record::`, `author::` or `statement_time::`; the tree is the note.
- Inside an `anf` block a concept is a grounded `k:Key[label] @id=<identity>` the knowledge base holds, a SNOMED CT identifier or the identifier a tool returned, or **`candidate[label] disp=PENDING`** when it lacks one. Never a block-local symbol such as `@id=rda-reopening`: those belong to the tree and complex-concept blocks only.
- Komet draws an ANF mark beside each node that holds a statement: resting on it shows the statement, a click opens it editable. A statement of the wrong type, or on a decision or chance, comes back as a finding.

## Answering

Put the block first, then two or three sentences: which concepts are local, and which assumptions the comparison is most sensitive to. **Do not compute, estimate or quote expected values, totals or the favoured alternative**: Komet rolls the tree back and shows every node's value and the chosen alternative beside it, and a number you work out in prose will disagree with the rendered one. If the person gives numbers, state them as parameters with `basis=cited` or `basis=estimated` as they told you; if they give none, say which you assumed and mark them `basis=estimated`.

When Komet reads your block and sends its findings back, restate your whole answer with the block corrected: change only what the findings name, keep every identity and every number the person gave, and do not explain the correction. A node Komet reports as not determinable lacks a value it can fold: give it the cost line, probability or parameter the finding names.
