# cloud-itonami-isic-6619

Open Business Blueprint for **ISIC Rev.5 6619**: other activities
auxiliary to financial service activities -- card transaction
processing and settlement. This repository publishes a card-
processing actor -- transaction intake, jurisdiction assessment, fraud
screening, settlement finalization and chargeback-hold release -- as
an OSS business that any qualified operator can fork, deploy, run,
improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102),
[`9103`](https://github.com/cloud-itonami/cloud-itonami-isic-9103),
[`9602`](https://github.com/cloud-itonami/cloud-itonami-isic-9602),
[`9000`](https://github.com/cloud-itonami/cloud-itonami-isic-9000),
[`8890`](https://github.com/cloud-itonami/cloud-itonami-isic-8890),
[`8610`](https://github.com/cloud-itonami/cloud-itonami-isic-8610),
[`9311`](https://github.com/cloud-itonami/cloud-itonami-isic-9311),
[`8510`](https://github.com/cloud-itonami/cloud-itonami-isic-8510),
[`9412`](https://github.com/cloud-itonami/cloud-itonami-isic-9412),
[`6491`](https://github.com/cloud-itonami/cloud-itonami-isic-6491),
[`8720`](https://github.com/cloud-itonami/cloud-itonami-isic-8720),
[`8521`](https://github.com/cloud-itonami/cloud-itonami-isic-8521)) --
a third financial-services vertical alongside `6492`'s credit granting
and `6491`'s financial leasing, but for card-payment processing/
settlement rather than lending. Here it is **Card Advisor ⊣ Card
Settlement Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> transaction-intake summary, normalizing records, and checking
> whether a transaction's own settlement amount actually stays within
> its own authorized amount -- but it has **no notion of which
> jurisdiction's card-settlement/chargeback-dispute requirements are
> official, no license to finalize a real settlement or release a
> real chargeback hold, and no way to know on its own whether a fraud
> flag against the transaction has actually stayed unresolved**.
> Letting it finalize a settlement or release a chargeback hold
> directly invites fabricated jurisdiction citations, a settlement
> over-charging its own granted authorization, and an unresolved
> fraud flag being quietly overlooked -- and liability, and financial
> risk, for whoever runs it. This project seals the Card Advisor into
> a single node and wraps it with an independent **Card Settlement
> Governor**, a human **approval workflow**, and an immutable **audit
> ledger**.

## Scope: what this actor does and does not do

This actor covers transaction intake through jurisdiction assessment,
fraud screening, settlement finalization and chargeback-hold release.
It does **not**, by itself, hold any license required to operate a
card-payment processor in a given jurisdiction, and it does not claim
to. It also does **not** handle a raw PAN (Primary Account Number) at
any point -- tokenization at the edge is the operator's own
responsibility, entirely out of scope for this actor's own SSoT (see
`card.store`'s own docstring: there is no PAN field in this actor's
schema at all, by design, not merely omitted-by-convention). It also
does **not** implement PAN/Luhn validation or ISO 8583 message
handling directly -- those are the responsibility of the related
capability contracts this blueprint names (see `Capability layer`
below), not this governed-actor scaffold. Whoever deploys and operates
a live instance (a licensed card-processing operator) supplies any
jurisdiction-specific license, the real PAN-tokenization/PCI-DSS
infrastructure and the real card-network integrations, and bears that
jurisdiction's liability -- the software supplies the governed, spec-
cited, audited execution scaffold so that operator does not have to
build the compliance layer from scratch for every new market.

### Actuation

**Finalizing a real settlement or releasing a real chargeback hold is
never autonomous, at any phase, by construction.** Two independent
layers enforce this (`card.governor`'s `:actuation/settle-
transaction`/`:actuation/release-chargeback` high-stakes gate and
`card.phase`'s phase table, which never puts `:settlement/finalize`/
`:chargeback/release` in any phase's `:auto` set) -- see `card.phase`'s
docstring and `test/card/phase_test.clj`'s `settlement-finalize-
never-auto-at-any-phase`/`chargeback-release-never-auto-at-any-phase`.
The actor may draft, check and recommend; a human processor officer is
always the one who actually finalizes a settlement or releases a
chargeback hold. Like `6512`/`6622`/`6520`/`6530`/`6820`/`6920`/
`6611`/`8530`/`9200`/`9521`/`8730`/`9102`/`9103`/`8890`/`8610`/`8510`/
`9412`/`8720`/`8521`, this actor has TWO actuation events.

## The core contract

```
transaction intake + jurisdiction facts (card.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Card         │ ─────────────▶ │ Card                          │  (independent system)
   │ Advisor      │  + citations    │ Settlement Governor:          │
   │ (sealed)     │                 │ spec-basis · evidence-       │
   └──────────────┘         commit ◀────┼──────────▶ hold │ incomplete ·
                                 │             │           │ settlement-amount-
                           record + ledger  escalate ─▶ human   exceeds-authorized
                                             (ALWAYS for         (MAXIMUM-ceiling,
                                              :settlement/            non-temporal) ·
                                              finalize /              fraud-flag-unresolved
                                              :chargeback/release)     (unconditional) ·
                                                                       already-settled/-released
```

**The Card Advisor never finalizes a settlement or releases a
chargeback hold the Card Settlement Governor would reject, and never
does so without a human sign-off.** Hard violations (fabricated
jurisdiction requirements; unsupported evidence; a settlement over-
charging its own authorization; an unresolved fraud flag; a double
settlement or chargeback-release) force **hold** and *cannot* be
approved past; a clean settlement/chargeback-release proposal still
always routes to a human.

## Run

```bash
kbb -M:dev:run     # walk one clean dual-actuation lifecycle + four HARD-hold cases through the actor
kbb -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
kbb -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

A live sample of the operator console is rendered in
[docs/samples/operator-console.html](docs/samples/operator-console.html)
-- pure-data HTML output of the kotoba-lang capability UI.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a card-fulfillment and ATM-
servicing robot handles physical card and cash logistics, under the
actor, gated by the independent **Card Settlement Governor**. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Card Settlement Governor, settlement-finalization + chargeback-release draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6619`). Related capability contracts are published as:

- [`kotoba-lang/card`](https://github.com/kotoba-lang/card) -- PAN/Luhn, ISO 8583, authorization
- [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking) -- clearing batches, settlement ledger
- [`kotoba-lang/swift`](https://github.com/kotoba-lang/swift) -- interbank settlement messaging

this actor's `card.*` namespaces are a self-contained governed
implementation -- it does not require any of these capability libs
directly, the same "self-contained sibling" relationship `credit.*`
(`6492`) and `leasing.*` (`6491`) have toward their own `:banking`
capability reference.

## Layout

| File | Role |
|---|---|
| `src/card/store.cljk` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate settlement-finalization/chargeback-release history. No raw PAN field anywhere in the schema. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded transaction, and the double-actuation guards check dedicated `:settled?`/`:chargeback-released?` booleans rather than a `:status` value |
| `src/card/registry.cljk` | Settlement-finalization + chargeback-release draft records, plus `settlement-amount-exceeds-authorized?` -- the THIRD non-temporal instance of this fleet's MAXIMUM-ceiling check family (`facility`/`school` established the first two), directly implementing this blueprint's own "partial approvals never over-charge the granted amount" Trust Control |
| `src/card/facts.cljk` | Per-jurisdiction card-settlement/chargeback-dispute catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/card/cardadvisor.cljk` | **Card Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/fraud-screening/settlement-finalization/chargeback-release proposals; never handles a raw PAN |
| `src/card/governor.cljk` | **Card Settlement Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · settlement-amount-exceeds-authorized, pure ground-truth MAXIMUM-ceiling recompute · fraud-flag-unresolved, unconditional evaluation, the TWENTY-FOURTH grounding of this discipline and FIRST specifically for the fraud-flag concept) + already-settled/already-released guards + 1 soft (confidence/actuation gate) |
| `src/card/phase.cljk` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (both settlement and chargeback-release always human; transaction intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/card/operation.cljk` | **OperationActor** -- langgraph-clj StateGraph |
| `src/card/sim.cljk` | demo driver |
| `test/card/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |
| `wasm/settlement_authorized.kotoba` | `.kotoba`-wasm port of `settlement-amount-exceeds-authorized?`'s pure comparison core, compiled via `kotoba wasm emit` and hosted under `kototama.tender` (see `wasm/README.md`) |

## Business-process coverage (honest)

This actor covers transaction intake through jurisdiction assessment,
fraud screening, settlement finalization and chargeback-hold release
-- the core governed lifecycle this blueprint's own `docs/business-
model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Transaction intake + per-jurisdiction card-settlement checklisting, HARD-gated on an official spec-basis citation (`:transaction/intake`/`:jurisdiction/assess`) | PAN/Luhn validation, ISO 8583 message handling, tokenization -- these are the related capability libs' concerns (`kotoba-lang/card`/`banking`/`swift`), not this actor's own SSoT |
| Fraud screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:fraud/screen`) | Real card-network/acquirer-processor integration, PCI-DSS infrastructure |
| Settlement finalization, HARD-gated on full evidence and settlement-amount sufficiency, plus a double-settlement guard (`:settlement/finalize`) | Ongoing authorization/clearing-batch-assembly workflows themselves |
| Chargeback-hold release, HARD-gated on full evidence and a double-release guard (`:chargeback/release`) | |
| Immutable audit ledger for every intake/assessment/screening/settlement/release decision | |

Extending coverage is additive: add the next gate (e.g. a merchant-
category-code-restriction check) as its own governed op with its own
HARD checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`card.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `card.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `card.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Card Advisor` + `Card Settlement Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the thirty-
three prior actors' architecture. See `docs/adr/0001-architecture.md`
for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
