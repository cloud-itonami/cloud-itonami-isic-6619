# cloud-itonami-6619

Open Business Blueprint for **ISIC Rev.5 6619**: other activities auxiliary
to financial service activities — card transaction processing and settlement.

This repository designs a forkable OSS business for community card-payment
processing: authorization, clearing, settlement, reconciliation and
chargeback — run by a qualified operator so a community keeps its own
merchant and settlement ledgers instead of renting them from a closed
processor.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a card-fulfillment and ATM-servicing robot handles physical card and cash logistics under an actor that proposes
actions and an independent **Card Settlement Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Core Contract

```text
intake + identity + PAN/token + merchant records
        |
        v
Card Advisor -> Card Settlement Governor -> authorize, clear, or human approval
        |
        v
ISO 8583 message + authorization + clearing batch + audit ledger
```

No automated advice can settle a transaction, override a decline, or release
a chargeback without governor approval and audit evidence.

A live sample of the operator console is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of the kotoba-lang capability UI.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6619`). Required capabilities are implemented by:

- [`kotoba-lang/card`](https://github.com/kotoba-lang/card) — PAN/Luhn, ISO 8583, authorization
- [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking) — clearing batches, settlement ledger
- [`kotoba-lang/swift`](https://github.com/kotoba-lang/swift) — interbank settlement messaging

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

Code and implementation templates are AGPL-3.0-or-later.
