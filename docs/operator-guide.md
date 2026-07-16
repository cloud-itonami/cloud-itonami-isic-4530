# Operator Guide

## First Deployment

1. Register the operator's storefront(s) and vendor relationships as independently verified records (`autoparts.store/with-storefronts` / `with-vendors`) -- an unverified party can never have a proposal committed on its behalf.
2. Import historical sales/inventory records and vendor directory.
3. Run read-only validation of existing records against this blueprint's contracts.
4. Configure the AutoPartsOpsGovernor's cost threshold (`autoparts.governor/supply-order-cost-threshold`) and rollout phase.
5. Publish a dry-run operation and audit export.

## Minimum Production Controls

- storefront/vendor verification required before any coordination proposal can commit
- a part-compatibility/counterfeit/recall concern flag always requires a human sign-off, at any confidence, any phase
- a supply-order proposal above the cost threshold always requires a human sign-off
- this actor never finalizes a part-compatibility certification or a recall-remediation determination, at any phase -- route those to the manufacturer, a certified inspection body, or the relevant regulator
- audit export for every hold, escalation and approval
- backup manual process for governor/system outage

## Certification

Certified operators must prove storefront/vendor-record integrity, governor
independence, the propose-only effect boundary, and human review for every
compatibility/counterfeit/recall concern and every above-threshold supply
order.
