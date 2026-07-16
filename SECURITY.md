# Security Policy

This project coordinates auto-parts-retail storefront/vendor operations. Treat
vulnerabilities as potentially high impact even when the demo data is
synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real storefront, vendor or customer data exposure
- authorization bypass (e.g. an unverified party's proposal committing)
- AutoPartsOpsGovernor bypass
- a code path that emits an `:effect` other than `:propose`
- a code path that directly finalizes a compatibility certification
- audit-ledger tampering
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on storefront/vendor data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real storefront/vendor/customer data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
