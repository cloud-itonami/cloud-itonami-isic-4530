# Governance

`cloud-itonami-isic-4530` is an OSS open-business blueprint for
community auto-parts-retail operations coordination.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a coordination proposal on behalf of an unverified/unregistered storefront or vendor can never commit.
- the AutoPartsOpsGovernor remains independent of the advisor.
- hard policy violations (out-of-scope op, spoofed non-`:propose` effect, direct compatibility-certification finalization) cannot be overridden by human approval.
- `:flag-compatibility-concern` always escalates to a human, at any phase.
- every commit, hold and escalation is auditable.
- storefront, vendor and sourcing data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:
- bypassing the verified-party gate or the propose-only effect boundary
- claiming to certify part compatibility or resolve a recall directly (permanently out of scope for this actor)
- mishandling storefront, vendor or customer data
- misrepresenting certification status
- failing to respond to security incidents
