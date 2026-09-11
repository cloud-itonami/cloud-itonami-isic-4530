# Contributing

`cloud-itonami-isic-4530` accepts contributions to the OSS blueprint, the
AutoPartsOpsGovernor's policy tests, documentation and operator model.

## Development

```bash
kbb -M:dev:test
kbb -M:lint
```

## Rules
- Do not commit real storefront, vendor, customer or sourcing data.
- Keep every commit behind the AutoPartsOpsGovernor -- no proposal writes the
  SSoT except through `autoparts.operation`'s `:commit` node.
- Never add a code path that could emit an `:effect` other than `:propose`,
  and never add a code path that could directly finalize a part-compatibility
  certification (see README `Scope`) -- both are permanent, structural
  boundaries, not implementation details to relax later.
- Treat auto-parts operations as high-risk: add tests for verified-party
  gating, the closed op-allowlist, escalation rules and audit logging.
- Before adding or changing a governor scope-exclusion check, confirm it is
  phrased as the finalization/execution ACTION (a structured field/keyword),
  never a bare noun scanned out of free text -- see
  `test/autoparts/governor_contract_test.cljk`'s
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion` for why.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
