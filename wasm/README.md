# wasm/ — kotoba-wasm deployment of the settlement-exceeds-authorized check

`settlement_authorized.kotoba` is a port of `card.registry/settlement-
amount-exceeds-authorized?`'s pure ground-truth comparison — does a
transaction's own settlement amount exceed its own authorized amount?
(see `src/card/registry.cljk` lines ~46-56, used by `card.governor/
settlement-amount-exceeds-authorized-violations` at `src/card/
governor.cljc` lines ~160-173) — into the minimal `.kotoba` language
subset, compiled to a real WASM module via `kotoba wasm emit`, and hosted
via `kototama.tender` (`test/wasm/settlement_authorized_test.cljk`).

This follows the same `kotoba wasm emit` → `kototama.tender` pattern
already proven by `cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`, and
`cloud-itonami-isic-6512`'s `wasm/claim_coverage.kotoba`
(ADR-2607062330 addendum 5) — this fleet's own THIRD non-temporal
MAXIMUM-ceiling check (`facility`/`school` established the first two on
the Clojure side; `claim_coverage.kotoba` is the closest prior wasm port
of the same shape) ported to real WASM.

## Why the source differs from `card.registry`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` — no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Ports ONLY the pure ground-truth arithmetic core — the direct comparison
  of `settlement-amount` against `authorized-amount` — never the
  `card.store/transaction` lookup or the `:settlement/finalize`
  op-dispatch, both of which stay in Clojure and never get ported (no
  maps, no protocols, no op-keyword dispatch in the wasm-compilable
  subset).
- Uses plain positional integer args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset).
- Drops the `(and (number? settlement-amount) (number? authorized-
  amount) ...)` type guard — a WASM i32 argument is already guaranteed to
  be an integer by the ABI itself, so the guard is structurally
  unreachable in this port (no `number?`/`and` in the wasm-compilable
  subset either).
- Compares `settlement-amount <= authorized-amount` directly as plain
  integers (smallest currency unit / cents) instead of dividing or using
  floats — no floats needed for a direct integer comparison, consistent
  with `cloud-itonami-isic-6492`/`cloud-itonami-isic-6512`/`kotoba-card`/
  `kotoba-banking`'s own convention of representing amounts as plain
  integers.
- Inverts the polarity relative to `card.registry`'s violation check:
  `settlement-amount-exceeds-authorized?` returns truthy (a violation)
  when the settlement EXCEEDS the authorized amount, whereas this
  module's `settlement-within-authorized?` (and `main`) returns `1` when
  the settlement is WITHIN the authorized amount (i.e. NOT a violation)
  and `0` when it exceeds it — the more natural "is this OK" polarity for
  a boolean-shaped WASM export, same polarity convention as
  `affordability.kotoba`'s `affordable?` and `claim_coverage.kotoba`'s
  `claim-within-coverage?`.

This is the simplest possible port: one direct comparison, no
multi-term formula, no zero-guard branch — structurally identical to
`cloud-itonami-isic-6512`'s `claim_coverage.kotoba`.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` — the
compiler only ever exports a 0-arity `main`, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead — the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba`,
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba`, and
`cloud-itonami-isic-6512`'s `claim_coverage.kotoba` use. A host writes
two little-endian i32 values (cents) before calling `main()`:

| offset | field                |
|--------|----------------------|
| 0      | `settlement-amount`  |
| 4      | `authorized-amount`  |

`main()` returns `1` (settlement within authorized — settle-eligible on
this check) or `0` (settlement exceeds authorized — a HARD
`:settlement-amount-exceeds-authorized` violation per `card.governor`).
Both offsets are well below `heap-base` (2048), so they never collide
with anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6619/wasm/settlement_authorized.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6619/wasm/settlement_authorized.wasm --json
```

Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
