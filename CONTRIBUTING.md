# Contributing

`cloud-itonami-6619` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

The capability layer lives in `kotoba-lang/card`, `kotoba-lang/banking` and
`kotoba-lang/swift`. This repo holds the business blueprint and operator
contracts.

```bash
# in kotoba-lang/card / banking / swift:
kbb -X:test
kbb -M:lint
```

Keep changes small and include tests for PAN/Luhn validation, ISO 8583
structure, partial-approval amounts, or clearing-batch balance.

## Rules

- Do not commit real PANs, tokens, credentials or cardholder records.
- Keep authorization, clearing and settlement behind the Card Settlement
  Governor.
- Treat card workflows as high-risk: add tests for Luhn, network,
  authorization, settlement, disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
