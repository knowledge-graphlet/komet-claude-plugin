---
name: Calculator
description: Use when the person asks for a clinical risk score or calculator over a patient's findings, such as the 10-year ASCVD risk of the Pooled Cohort Equations, what a missing input would do to it, or whether a treatment is worth starting given that risk.
category: Skill
---
A medical calculator is two fenced blocks, and Komet does the arithmetic. **Never compute the risk yourself, never quote a number the block will show, and never fill a missing input with a guess**: the `calculator` block states the method once, the `calculate` block binds the document's ANF statements to its inputs, and Komet shows the result as an interval, the inputs that widen it, and a tornado of which input to ask for first. A `decision-tree` block in the same reply may read the result as a parameter.

## The method, `calculator`

One line per vertex or value, two spaces of indentation per level. Write it once per document; a calculator the document already defines is only referenced.

```calculator
calculator: k:AscvdRisk10[10-year ASCVD risk] @id=pce source=goff2013
  output = k:AscvdRiskScore[10-year ASCVD risk] @id=ascvd-risk in k:PctPce2013[% over 10 years, PCE 2013] @id=pct-pce-2013 domain [0,100] scale=100
  input: k:Age[Age] @id=age in k:Year[year] @id=year domain [40,79] required
  input: k:Sex[Sex] @id=sex values=male;female required
  input: k:Hdl[HDL cholesterol] @id=hdl in k:MgPerDl[mg/dL] @id=mg-per-dl domain [20,100] when-missing=plausible
    missing = [35,70]
  input: k:TotalChol[Total cholesterol] @id=tc in k:MgPerDl[mg/dL] @id=mg-per-dl domain [130,320] required
    conversion = [38.67,38.67] k:MmolPerL[mmol/L] @id=mmol-per-l
  term = k:LnAge[ln age] @id=ln-age from NaturalLogarithm of k:Age[] @id=age
  term = k:LnAgeLnHdl[ln age x ln HDL] @id=ln-age-ln-hdl from Product of k:LnAge[] @id=ln-age k:LnHdl[] @id=ln-hdl
  stratum: k:WhiteMale[White male] @id=white-male
    when = k:Sex[] @id=sex k:Male[Male] @id=male
    coefficient = [12.344,12.344] k:LnAge[] @id=ln-age basis=cited source=goff2013
    coefficient = [0.91436,0.91436] k:S0[baseline survival] @id=s0 basis=cited source=goff2013
    coefficient = [61.1816,61.1816] k:Mean[mean of the terms] @id=mean basis=cited source=goff2013
  formula = from CoxSurvival of k:S0[] @id=s0 k:Mean[] @id=mean
  band: k:Low[low] @id=low when [0,5) source=grundy2018
```

- `calculator:` the root, the calculator's concept; `source=` the publication it comes from.
- `output =` the determination's topic, `in` the semantic the result is written in, its `domain`, and `scale=100` when a share is shown as a percentage.
- `input:` one quantity the equation reads: `in` the semantic it expects, `domain` the interval it is valid over, `values=` the concepts a concept-valued input admits, `required`, and `when-missing=not-determinable|plausible|assumed`. Beneath it, `missing =` the plausible range or the assumed value, `conversion =` a factor from another semantic, `recency =` how old a statement may be.
- `term =` a quantity derived from inputs and other terms: `from NaturalLogarithm of …`, `from Product of …`, `from Complement of …`, or `from k:Input[] @id=…` alone to read an input as it is.
- `stratum:` one published equation of the family, selected by `when =` lines (an input, then the value it must carry); its `coefficient =` lines carry the published numbers, each `basis=cited` with its `source=`, including the stratum constants the formula reads.
- `formula =` the kind that folds a stratum into the result: `CoxSurvival of` the baseline survival then the mean.
- `band:` a guideline's interval of the result, `when [lo,hi)`.

Write every coefficient of the published equation as published; never round or drop one. A missing presence input is never assumed absent unless the method says `when-missing=assumed` with `missing = [0,0]`.

## The record, `calculate`

```calculate
calculate: k:AscvdRisk10[10-year ASCVD risk] @id=pce
  bind = k:Age[Age] @id=age anf:<statement identity> by latest
  bind = k:Sex[Sex] @id=sex k:Male[Male] @id=male
  bind = k:TotalChol[Total cholesterol] @id=tc [5.5,5.5] k:MmolPerL[mmol/L] @id=mmol-per-l
  bind = k:Hdl[HDL cholesterol] @id=hdl missing
  bind = k:Smoker[Current smoker] @id=smoker clarify "smoking status is not in the note"
```

- `bind =` the input, then what binds: `anf:<identity>` an ANF statement the knowledge base holds, optionally `by latest`; an inline value, `[lo,hi]` with its semantic, or a concept, when the person states the value and no statement exists; `missing` when nothing is known, so the method's policy decides; `clarify "…"` when it should be asked.
- `assume =` an input and a value, beside whatever binds: a what-if the person asked for, visibly hypothetical. Never write an observed value as an assumption, and never change a bound statement.

## Reading the result with a tree

A `decision-tree` block in the same reply reads the result by naming the calculator: `p = from k:AscvdRisk10[] @id=pce`, with nothing after it. Komet hands the tree the risk as an interval, so a decision that straddles its break-even is left open until the missing fact arrives.

## Tokens and identities

Inside these blocks a concept is always `k:Key[label] @id=<identity>`: a concept the knowledge base holds carries the identifier a tool returned; a concept it lacks, a quantity or an equation term, carries a short symbol of your own, the same symbol wherever it recurs, which Komet draws as a dashed chip. Never write `k:uuid=` inside a block.

## Answering

Put the blocks first, the method before the record, then two or three sentences: which inputs have no statement and what the method does about each, and which concepts are local. **Do not compute, estimate or quote the risk, the band or the tree's values**: Komet shows them, and a number worked out in prose will disagree with the rendered one. When Komet sends findings back, restate the whole answer with the blocks corrected, changing only what the findings name.
