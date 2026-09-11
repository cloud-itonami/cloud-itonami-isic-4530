# cloud-itonami-isic-4530

Open Business Blueprint for **ISIC Rev.5 4530**: Sale of motor vehicle
parts and accessories -- sales-record logging, restocking-schedule
coordination, part-compatibility/counterfeit/recall concern flagging,
and supplier procurement-order coordination for a community auto-parts
retailer/wholesaler.

This repository publishes an auto-parts-retail OPERATIONS COORDINATION
actor as an OSS business that any qualified operator can fork, deploy,
run, improve and sell, so a regional parts retailer never surrenders
inventory, sourcing and compliance-signal data to a closed POS /
inventory-AI SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **PartsOpsAdvisor ⊣
AutoPartsOpsGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:auto-parts-ops-governor`, is a
UNIQUE keyword fleet-wide (grep-verified: no other blueprint declares
it) -- a fresh, independent build.

> **Why an actor layer at all?** An LLM is great at drafting a sales
> summary, normalizing a restock request, and reading a concern report
> -- but it has **no license to decide whether a storefront or a
> vendor is actually who it claims to be, no authority to directly
> certify that a part fits a vehicle or that a recall has been
> remediated, and no way to know on its own when a proposal has
> quietly drifted outside the four things this actor is scoped to
> do**. Letting it commit records directly invites a proposal on
> behalf of an unregistered pop-up storefront, a procurement order
> routed to an unverified grey-market importer, a silently-expanded
> scope (an "effect" that is no longer just a draft proposal), or --
> worst of all -- an LLM that talks itself into directly resolving a
> part-compatibility or recall concern instead of just surfacing it.
> This project seals the PartsOpsAdvisor into a single node and wraps
> it with an independent **AutoPartsOpsGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers sales-record logging, restocking-schedule
coordination, part-compatibility/counterfeit/recall concern flagging,
and supplier procurement-order coordination. **It does NOT, by itself,
hold any retail license, and it does NOT act as a part-compatibility
certification authority or a recall-remediation authority.** Those
judgments belong to the vehicle/part manufacturer, a certified
inspection body, or the relevant regulator -- this actor only
COORDINATES: it drafts a record of what a human/downstream system
should do next, it never itself restocks a shelf, transmits a purchase
order to a vendor, settles a sale, or certifies/finalizes anything.
Whoever deploys and operates a live instance (a qualified parts
retailer/wholesaler) supplies any jurisdiction-specific retail license,
the real POS / warehouse-management / procurement-system integration,
and bears that jurisdiction's liability -- the software supplies the
governed, audited coordination scaffold so that operator does not have
to build the compliance layer from scratch.

### Actuation

**Every proposal this actor ever commits carries `:effect :propose`,
by construction -- there is no code path in `autoparts.partsopsadvisor`
that can ever emit any other effect, and `autoparts.governor`'s
`effect-propose-only` HARD gate independently re-verifies this on every
proposal.** A part-compatibility-certification finalization is a
PERMANENT, un-overridable block (`compatibility-certification-
finalization-block`), checked via structured fields only -- never by
scanning free-text rationale for a bare noun, a documented self-trip
bug class this fleet has independently hit and fixed more than once
(see `test/autoparts/governor_contract_test.cljk`'s
`default-mock-advisor-proposals-never-self-trip-scope-exclusion`).
`:flag-compatibility-concern` ALWAYS escalates to a human, at any
confidence, any phase -- two independent layers enforce this
(`autoparts.governor`'s `:always-escalate?` rule and
`autoparts.phase`'s phase table, which never puts `:flag-compatibility-
concern` in any phase's `:auto` set). The actor may draft, check and
recommend; a human parts coordinator is always the one who actually
restocks a shelf, transmits a purchase order, or acts on a
compatibility/recall concern.

## The core contract

```
storefront/vendor directory (independently verified/registered)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────────┐
   │ PartsOpsAdvisor        │ ─────────────▶ │ AutoPartsOpsGovernor      │  (independent system)
   │ (sealed)               │  + citations    │ closed-op-allowlist ·     │
   └───────────────────────┘                  │ verified-party-gate ·     │
          │                 commit ◀┼ effect-propose-only ·             │
          │                         │ compatibility-certification-      │
    record + ledger        escalate ┼ finalization-block                │
          │              (ALWAYS for│                                   │
          │       :flag-compat-     │                                   │
          │        ibility-concern) │                                   │
          ▼                          └───────────────────────────┘
      human approval
```

**The PartsOpsAdvisor never commits a proposal the AutoPartsOpsGovernor
would reject, and `:flag-compatibility-concern` never commits without a
human sign-off.** Hard violations (an out-of-scope op; an unverified
storefront or vendor; a proposal claiming any effect other than
`:propose`; any attempt to directly finalize a compatibility
certification) force **hold** and *cannot* be approved past; a clean,
verified, in-scope proposal for the other three ops may still route to
a human when confidence is low or (for supply orders) the estimated
cost exceeds the threshold.

## Run

```bash
clojure -M:dev:run     # walk clean coordination proposals + a compatibility-concern flag through the actor, then six HARD-hold scenarios
clojure -M:dev:test    # governor contract · phase invariants · store parity
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an autonomous warehouse
picking/restocking robot (or the storefront's own robotic shelf-
replenishment system) is the physical actuator that WOULD carry out a
restock this actor's `:schedule-restocking-operation` proposal
coordinates -- the actor never dispatches hardware itself: any
downstream restocking action must have cleared the same coordination
record a human parts coordinator would review. This restates the
fleet-wide robotics premise three ways (ADR-2607011000): the blueprint
declares `:robotics true`, the README names the robot that would
perform the physical act, and the AutoPartsOpsGovernor is the
independent gate that robot's dispatch command must pass through
before any downstream system acts on this actor's coordination record.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, AutoPartsOpsGovernor, coordination-log drafts, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4530`). This vertical is NOT backed by a separate bespoke domain
capability lib: the coordination-record construction is a self-
contained pure-function namespace (`autoparts.registry`), on top of the
generic robotics/identity/forms/dmn/bpmn/audit-ledger/retail stack.

## Layout

| File | Role |
|---|---|
| `src/autoparts/store.cljk` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`kotoba-lang/langchain-store`). Adopts BOTH the entity-store pattern (storefront/vendor) and the seq-keyed event-stream pattern (ledger + the four coordination logs) |
| `src/autoparts/registry.cljk` | Pure-function coordination-record construction. Deliberately has NO certificate-issuing function -- this actor never certifies anything |
| `src/autoparts/facts.cljk` | Small, honest catalog of real public sources a compatibility-concern flag MAY cite (informational, not a governor gate in this R0) |
| `src/autoparts/partsopsadvisor.cljk` | **PartsOpsAdvisor** -- `mock-advisor` ‖ `llm-advisor`; sales-record / restock / concern-flag / supply-order proposals, every branch hard-coded to `:effect :propose` |
| `src/autoparts/governor.cljk` | **AutoPartsOpsGovernor** -- 4 HARD checks (closed-op-allowlist · verified-party-gate · effect-propose-only · compatibility-certification-finalization-block) + 3 SOFT escalate rules (confidence floor · compatibility-concern always-escalate · supply-order cost-threshold) |
| `src/autoparts/phase.cljk` | **Phase 0→3** -- read-only → assisted logging → assisted coordination → supervised (compatibility-concern flags always human; the other three ops are auto-eligible when clean) |
| `src/autoparts/operation.cljk` | **OperationActor** -- langgraph StateGraph |
| `src/autoparts/sim.cljk` | demo driver |
| `test/autoparts/*_test.cljc` | governor contract (incl. the mandatory self-trip regression test) · phase invariants · store parity |

## Business-process coverage (honest)

This actor covers sales-record logging, restocking-schedule
coordination, compatibility-concern flagging, and supply-order
coordination -- the core governed coordination lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Sales-record / inventory-movement logging, verified-party-gated (`:log-sales-record`) | Real POS / warehouse-management / procurement-system integration |
| Restocking-schedule coordination drafts (`:schedule-restocking-operation`) | Part-compatibility certification, recall-remediation determination (permanently out of scope, not a rollout milestone) |
| Compatibility/counterfeit/recall concern flagging, ALWAYS human-reviewed (`:flag-compatibility-concern`) | Real payment settlement / purchase-order transmission |
| Supplier procurement-order coordination drafts, cost-threshold gated (`:coordinate-supply-order`) | |
| Immutable audit ledger for every coordination decision | |

Extending coverage is additive: add the next coordination op as its own
governed op with its own HARD checks and tests, following the SAME
"an independent governor re-verifies against the actor's own records
before any real-world act" pattern this repo's flagship ops already
establish.

## Maturity

`:implemented` -- `PartsOpsAdvisor` + `AutoPartsOpsGovernor` run as
real, tested code (see `Run` above), following the SAME governed-actor
architecture as the other prior actors across this fleet, with its own
distinct, independently-named governor. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
