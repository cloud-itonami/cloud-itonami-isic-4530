# ADR-0001: cloud-itonami-isic-4530 -- PartsOpsAdvisor as a contained intelligence node

- Status: Accepted (2026-07)
- Related: `cloud-itonami-isic-4510` ADR (VehicleSaleGovernor, the closest
  sibling in ISIC division 45), `cloud-itonami-isic-4730` ADR (Forecourt
  Safety Governor, the most recent self-contained-vertical port),
  `cloud-itonami-isic-6511` ADR-0001 (UnderwritingGovernor, the reference
  `kotoba-lang/langchain-store` entity-store adopter), langgraph-clj
  ADR-0001 (Pregel superstep + interrupt + Datomic checkpoint)
- Context: Wave 2 (coordination/logistics/trade, ADR-2607121000) fresh
  scaffold for ISIC `4530` -- "Sale of motor vehicle parts and
  accessories" (auto-parts retail/wholesale), distinct from the sibling
  `4520` (motor-vehicle repair) and `4540` (motorcycle sale/repair)
  classes built alongside it in the same batch.

## Problem

An auto-parts retailer/wholesaler needs to coordinate four distinct
kinds of operational judgment:

1. **Sales/inventory-movement record keeping** -- normalizing what was
   sold, returned, or adjusted, without inventing SKU/quantity/amount
   data.
2. **Restocking-schedule coordination** -- drafting a proposal for a
   human/warehouse system to act on.
3. **Part-compatibility/counterfeit/recall concern surfacing** -- an
   LLM has no authority to decide whether a part actually fits a
   vehicle, whether it is counterfeit, or whether a recall applies; it
   can only flag a concern for a qualified human/manufacturer/regulator
   to resolve.
4. **Supplier procurement-order coordination** -- drafting a proposal
   to a human buyer, never transmitting a real purchase order or
   settling a real payment.

An LLM has no grounding for any of these, and -- critically -- no
structural reason NOT to drift into acting as if it WERE the
compatibility-certification or recall-remediation authority itself.
The design problem is therefore "seal the LLM inside a trust boundary
that can only ever propose, verify every party it proposes on behalf
of, and make directly finalizing a compatibility certification a
permanent, un-overridable, structurally-absent capability -- not a
policy the LLM is merely asked to respect."

## Decision

### 1. PartsOpsAdvisor is sealed into the bottom node; it never commits directly, and never emits any effect but `:propose`

`autoparts.partsopsadvisor` returns exactly four kinds of proposal:
sales-record normalization, restocking-schedule draft, compatibility-
concern flag, and supply-order-coordination draft. Every branch hard-
codes `:effect :propose` -- there is no code path in this namespace
that could ever emit a different effect value, and `autoparts.registry`
has no certificate-issuing function of any kind (unlike sibling actors
that draft an unsigned certificate for their own governed act). The
structural absence is deliberate: there is nothing to call even if the
governor were bypassed.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 coordination operation

`autoparts.operation/build` is the same StateGraph shape as every prior
actor in this fleet (intake → advise → govern → decide → commit | hold
| request-approval). One graph run corresponds to one coordination
operation, with no unbounded inner loop.

### 3. AutoPartsOpsGovernor is a separate system from PartsOpsAdvisor

`autoparts.governor` has four HARD checks (closed-op-allowlist ·
verified-party-gate · effect-propose-only · compatibility-
certification-finalization-block) and three SOFT rules (confidence
floor · compatibility-concern always-escalate · supply-order cost-
threshold).

### 4. The compatibility-certification-finalization block is checked via structured fields only -- never free-text scanning

Multiple sibling actors in this fleet have independently discovered and
fixed the SAME bug class: a governor's scope-exclusion term phrased as
a bare noun (e.g. "compatibility", "certification") accidentally
matches inside the mock advisor's own DEFAULT rationale/disclaimer text
for a legitimate, in-scope proposal -- causing the actor to self-block
on its own happy path. `compatibility-certification-finalization-
violations` checks only an exact `:op` match against
`banned-finalization-ops` and exact boolean flags inside `:value`
(`:compatibility-certification-finalized?` / `:certification-
finalize?`) -- both phrased as the finalization/execution ACTION, never
a bare noun -- and never scans `:rationale`/`:summary` prose.
`test/autoparts/governor_contract_test.cljk`'s
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` is the
executable regression proof: it runs the mock advisor's own default
proposal for all four in-scope ops against clean, verified demo data
and asserts none of them trip this rule.

### 5. Real actuation is structurally always out of scope, not merely human-gated

Unlike a sibling actor whose real-world act (e.g. dispensing fuel,
binding a policy) is human-gated but still ultimately COMMITTED by this
actor, this actor's scope stops one layer earlier: it never commits
anything but a coordination draft. `:flag-compatibility-concern` is
additionally structurally excluded from every phase's `:auto` set
(`autoparts.phase`), enforced independently by the governor's
`:always-escalate?` rule -- two layers, neither depending on the other
being implemented correctly, mirroring `cloud-itonami-isic-4730`'s
dual-layer `:pump/dispense`/`:sale/settle` pattern.

### 6. Store adopts BOTH `kotoba-lang/langchain-store` patterns (ADR-2607141600)

`autoparts.store` uses the entity-store pattern (`ls/map->tx` /
`ls/pull->map` / `ls/pull-pattern`, the `underwriting.store` /
`cloud-itonami-isic-6511` discipline) for `storefront`/`vendor`, and the
seq-keyed event-stream pattern (`ls/read-stream` / `ls/append-blob!`)
for the audit ledger and the four coordination logs. No hand-rolled
`enc`/`dec*` codec -- the exact two-liner ~190 sibling stores
duplicate.

### 7. No fabricated compatibility-provenance standard

`autoparts.facts` cites two real sources (NHTSA recalls; a structural
operator-registered OEM-fitment-catalog class) but this citation is
informational only in this R0, not a governor-enforced gate -- this
actor's scope is coordination (surfacing a concern), not adjudicating
whether a citation is sufficient to resolve one.

## Consequences

- (+) Auto-parts retail/wholesale operations coordination gets the same
  governed, auditable-actor treatment as motor-vehicle sale
  (`cloud-itonami-isic-4510`) and automotive-fuel retail
  (`cloud-itonami-isic-4730`), without centralizing liability in one
  vendor -- any qualified retailer/wholesaler can fork and run their
  own instance.
- (+) The scope-exclusion invariant (never certify compatibility) is
  regression-tested by both a dedicated adversarial unit test
  (`direct-certification-finalization-flag-is-a-hard-permanent-
  violation`) and the mandatory happy-path non-self-trip test.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/autoparts/store_contract_test.cljk`, the same `:db-api`-driven
  swap pattern every sibling store uses.
- (-) This R0 does not integrate a real POS, warehouse-management, or
  procurement system -- each operator's responsibility.
- (-) `autoparts.facts`' citation catalog seeds only 2 real sources;
  `coverage` reports this honestly rather than claiming broader
  coverage.
- 23 tests / assertions across governor contract, phase invariants and
  store parity, lint clean (see README `Run` for the exact commands
  and raw counts as verified at merge time).

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Let `:coordinate-supply-order` auto-commit at any cost when clean | ❌ | The task's own escalation spec requires a cost threshold; an unconditionally-auto-eligible supply order would let a large, unreviewed spend commitment slip through at phase 3 |
| Also exclude `:coordinate-supply-order` from every phase's `:auto` set (mirroring `:flag-compatibility-concern`) | ❌ | Over-broad: the task specifies supply orders escalate only ABOVE a cost threshold, not always -- a below-threshold, clean, verified order auto-committing at phase 3 is the intended behavior, not a gap |
| Check the compatibility-certification-finalization block by scanning `:rationale`/`:summary` text for terms like "compatibility"/"certification" | ❌ | This is the EXACT documented self-trip bug class this fleet has independently hit and fixed more than once -- the advisor's own legitimate disclaimer text for `:flag-compatibility-concern` contains these words |
| Give `autoparts.registry` a certificate-issuing function, gated only by the governor | ❌ | Leaves a latent capability that a governor bug/bypass could exercise; the task's "hard, permanent block" is better served by there being no certificate-builder to call at all |
