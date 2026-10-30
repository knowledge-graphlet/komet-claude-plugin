---
name: Decision tree
description: Use when the person asks to draw, state, read, compare or roll back a decision tree, a decision analysis, or the expected cost or value of alternatives under uncertainty: a decision among alternatives, with chances, events and outcomes that carry probabilities and money or health costs.
category: Skill
---
State a decision tree as a fenced `decision-tree` block and let Komet draw it: the tree in decision-analytic notation, every node's concept as a chip, and the value each node is left with after rollback under the valuation coordinate. **Never roll the tree back yourself and never draw it as box art or a nested list**: the renderer folds expected cost at a chance, least cost at a decision, and prices health into money at the health price the block's info sets (`health-price=100000`, in the money semantic's units; the reader can move it).

## The block

One line per vertex or value, two spaces of indentation per level; indentation carries the edges.

```decision-tree health-price=100000
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
- `outcome:` a leaf. A node may carry `statement=<identity>;<identity>` naming the ANF statements that say what it is.

### Values, `word = ...`

- `p = [lo,hi]` a probability on an event; `money = [lo,hi] k:Unit[label] @id=<identity>` a cost in a currency; `health = [lo,hi] k:Unit[label] @id=<identity>` a cost in healthy life (QALYs), priced into money by the health price. A cost line sits under the node that pays it; a benefit is a negative cost. **The token after a cost's interval is its unit, one currency concept for every money line and one QALY concept for every health line** (`k:Usd[US dollar] @id=rda-usd`, `k:Qaly[QALY] @id=rda-qaly`); it is never a quantity. A node carries at most one money line and one health line; a risk that may or may not happen is a chance beneath the node, with the cost on its event, not an expected cost on the node.
- An interval is `[lo,hi]`, a point `[x,x]`, an open end `(` or `)`, no bound `inf`; the renderer carries the spread through rollback.
- `basis=estimated|cited|derived` says where a number comes from; `source=<identity>` names the citation.
- `parameter = [lo,hi] k:Quantity[label] @id=<identity> ...` states a model assumption once, under the root; any value may then be `from <kind> of <quantities>`: kinds are `Complement` (1 − x), `Product`, `CumulativeHazard` (1 − (1 − h)^n), `BayesPosterior` (prior, sensitivity, false-positive rate) and `BayesMarginal` (the same three; the share that tests positive). A derived value has no interval of its own. A value that is simply one of the parameters is written `from` that quantity alone, with nothing after it: `p = from k:Reopening[] @id=rda-reopening-hazard` — never restate the number by hand where a parameter already states it.
- **A derived parameter names its quantity first, then the derivation**: `parameter = k:Risk[Twenty-year rupture risk] @id=rda-risk from CumulativeHazard of k:Hazard[] @id=rda-hazard k:Years[] @id=rda-years`. The quantity never comes after the operands.

### Tokens and identities

Inside this block a concept is always `k:Key[label] @id=<identity>`: a short CamelCase key, the label in brackets, and the identity after `@id=`. **This is not the Koncept Badge form** used in prose; never write `k:uuid=` or `k:sctid=` inside the block.

- A concept the knowledge base holds carries the **exact identifier a tool returned** (`search`, `concept`) as its identity: `k:Aneurysm[Intracranial aneurysm] @id=<the UUID the tool returned>`. It then draws as the shared Koncept chip.
- A concept the knowledge base lacks (a quantity, a currency, a situation the model names) carries a short symbolic identity of your own, `@id=rda-usd`, the same symbol wherever it recurs; Komet reads it as a concept local to the block, draws it as a dashed chip, and lists the symbols beneath the tree. Never invent a store identifier for one.
- A derivation kind is written as its bare name after `from` (`from CumulativeHazard of ...`) or as a token with no identity (`from k:CumulativeHazard[] of ...`); its operands after `of` are the quantities' tokens, each with the quantity's `@id=`.

## Answering

Put the block first, then two or three sentences: which concepts are local, and which assumptions the comparison is most sensitive to. **Do not compute, estimate or quote expected values, totals or the favoured alternative**: Komet rolls the tree back and shows every node's value and the chosen alternative beside it, and a number you work out in prose will disagree with the rendered one. If the person gives numbers, state them as parameters with `basis=cited` or `basis=estimated` as they told you; if they give none, say which you assumed and mark them `basis=estimated`.

When Komet reads your block and sends its findings back, restate your whole answer with the block corrected: change only what the findings name, keep every identity and every number the person gave, and do not explain the correction. A node Komet reports as not determinable lacks a value it can fold: give it the cost line, probability or parameter the finding names.
