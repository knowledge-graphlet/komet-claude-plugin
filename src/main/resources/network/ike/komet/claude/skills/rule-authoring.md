---
name: Rule authoring
description: Use when the person asks to write, add, change or test a rule in Komet, or asks what Komet's rules offer for a component.
category: Skill
---
Write a rule the way Komet's own rules are written, propose it, and let the person decide. A rule you write is Java that runs inside Komet, so it is never in use until the person accepts it in the Rules card.

## 1. Know what a rule is here

Komet fires its rules when something is observed. Today that is mainly when a component is focused, to build its context menu. Each rule whose conditions hold makes an offer: an action the person may choose. A rule decides and offers; it does nothing else.

A rule set is one Java class. Each rule is a method of that class.

## 2. Look before you write

- `list_rules` names every rule set and every rule. A rule's name must be one no other rule has, in any rule set, so read the names first.
- `run_rules` on a component the person cares about shows what the rules offer for it now.
- If the person wants a rule written in Komet changed, `read_rule` gives its source and its id.

## 3. Write the source

Start from this shape and change the class name, the rule's name, the conditions and the body.

```java
package authored.rules;

import dev.ikm.komet.framework.performance.impl.ObservationRecord;
import dev.ikm.komet.rules.annotated.RulesBase;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.entity.EntityVersion;
import org.evrete.dsl.annotation.MethodPredicate;
import org.evrete.dsl.annotation.Rule;
import org.evrete.dsl.annotation.Where;

public class OfferInactivationOfConcepts extends RulesBase {

    @Rule("Offer to inactivate an active concept")
    @Where(methods = {
            @MethodPredicate(method = "isComponentFocused", args = {"$observation"}),
            @MethodPredicate(method = "isConceptVersion", args = {"$observation"}),
            @MethodPredicate(method = "isComponentActive", args = {"$observation"})})
    public void offerInactivation(ObservationRecord $observation) {
        if ($observation.subject() instanceof EntityVersion version) {
            offer(KometConcept.INACTIVATE_COMPONENT, version.entity());
        }
    }
}
```

The example repeats an offer Komet already makes. It shows the shape only; do not propose it as it is.

What must hold, or Komet returns a finding:

- One public class that extends `RulesBase`. Name every import; no wildcard imports.
- Each rule is a `public void` method with `@Rule("…")`. Give every rule a name in plain words that no other rule has. A rule without a name takes its method's name, which is easily taken already.
- The rule's parameter is the fact it matches, named with a leading `$`, and the same name appears in `args`. For a focused component that is `ObservationRecord $observation`.
- Every condition is a `boolean` method, named in `@Where(methods = {@MethodPredicate(method = "…", args = {"$observation"})})`. A condition written as text, `@Where("…")`, is refused.
- A condition is one of `RulesBase`'s methods or a `public boolean` method you add to the class, taking the same parameter.

The conditions `RulesBase` has for an `ObservationRecord`:

- `isComponentFocused`: a component is focused. Its subject is the component's latest version, an `EntityVersion`.
- `isComponentActive`, `isComponentInactive`: that version is active, or inactive.
- `isConceptVersion`: the focused component is a concept.
- `isAxiomFocused`: a part of a definition is focused, with `isDefinitionRoot`, `isNotDefinitionRoot`, `isAxiomSet`, `isAxiomConcept`, `isAxiomRoleGroup`, `isAxiomRoleOnly`, `isAxiomIntervalRole` and `isAxiomFeature` to say which part.

What the body may do:

- `offer(action, subject)` offers an action concept on an entity. Komet must have an implementation for the action concept: `KometConcept.ACTIVATE_COMPONENT` and `KometConcept.INACTIVATE_COMPONENT` are always there. If you are not sure an action concept has one, ask the person rather than guess.
- `calculator()` is the view the person reads the knowledge base through, for a condition that depends on what the knowledge base says.

What it must not do: write to the knowledge base, open a window, read or write files, use the network, start a thread, or keep state between firings. The rule runs every time a component is focused, so it must be quick and have no effect but its offers.

## 4. Propose it and read the findings

`propose_rule` with a name for the rule source and the whole Java source. Komet compiles it at once.

- The name becomes the name of the rule set in the knowledge base once the person accepts it, and the proposal's `id` derives from it. Choose a name in plain words that no other rule set has, and keep it: a proposal made again with its `id` keeps its first name. A name a rule set in use already has makes your proposal an edit of that rule set, and the result says so.

- A finding names a line of the source you sent. Fix what it says and propose again with the same `id`, so the proposal is replaced rather than joined by a second one.
- "A rule named … is already in use" means the name is taken: give the rule another name.
- Do not stop at the first proposal that compiles if it does not do what the person asked. Read your own source once more.

## 5. Hand it to the person

Tell the person the rule is proposed and where to decide: the Rules card, which they add from the journal's **+** menu. There they read the source, then choose Accept or Discard. Say in a sentence what the rule will offer and when. You cannot accept a rule, and it does nothing until they do.

An accepted rule set is kept in the knowledge base as an unpublished change, and Komet puts it in use again each time it starts on the person's computer. On another computer the same knowledge base lists it as not accepted here, and it is not in use there until someone accepts it there. Each rule becomes a concept named by the sentence in its `@Rule`, which is one more reason to write that sentence in plain words. A proposal is not kept: one the person has not decided on is gone when Komet quits.

To change a rule already in use, propose the new source with that rule source's `id`. The rule in use stays as it is until the person accepts the edit.

The person can also edit any rule written in Komet themselves on the Rules card, with Edit, and write one with New. What they save is in use at once, so read the rule again with `read_rule` before you build on it.

## 6. Check it after it is accepted

When the person says they accepted it, `run_rules` on a component the rule should match and on one it should not, and report what was offered. If the result says the rule source was taken out of use because a rule threw, read the line it names, fix the source, and propose the edit with the same `id`.
