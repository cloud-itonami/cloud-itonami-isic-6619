(ns card.registry
  "Pure-function settlement-finalization + chargeback-release record
  construction -- an append-only card-processor book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a settlement-finalization or
  chargeback-release reference number -- every processor/jurisdiction
  assigns its own reference format. This namespace does NOT invent
  one; it builds a jurisdiction-scoped sequence number and validates
  the record's required fields, the same honest, non-fabricating
  discipline `card.facts` uses.

  `settlement-amount-exceeds-authorized?` is the THIRD non-temporal
  instance of this fleet's MAXIMUM-ceiling check family (`facility.
  registry/occupancy-exceeds-capacity?` established the first,
  `school.registry/class-size-exceeds-maximum?` the second), directly
  implementing this blueprint's own stated Trust Control: 'partial
  approvals never over-charge the granted amount' -- a transaction's
  own settlement amount must never exceed its own authorized amount.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real card-processing/settlement system, and it never
  touches a raw PAN (see README `Actuation` -- tokenization is the
  operator's own responsibility at the edge, out of scope for this
  actor). It builds the RECORD a processor would keep, not the act of
  finalizing the settlement or releasing the chargeback hold itself
  (that is `card.operation`'s `:settlement/finalize`/`:chargeback/
  release`, always human-gated)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  processor's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn settlement-amount-exceeds-authorized?
  "Does `transaction`'s own `:settlement-amount` exceed its own
  `:authorized-amount`? A pure ground-truth check against the
  transaction's own permanent fields -- no upstream comparison needed.
  The THIRD non-temporal instance of this fleet's MAXIMUM-ceiling
  check family (see ns docstring), directly implementing this
  blueprint's own 'partial approvals never over-charge the granted
  amount' Trust Control."
  [{:keys [settlement-amount authorized-amount]}]
  (and (number? settlement-amount) (number? authorized-amount)
       (> settlement-amount authorized-amount)))

(defn settlement-amount-exceeds-authorized-checkable?
  "Are both sides of `settlement-amount-exceeds-authorized?` actually recorded?

  That predicate answers only `over` / `not over`, and its
  `(and (number? ...) (number? ...) ...)` guard made every un-recorded
  case fall through as `not over` -- an entity missing either figure
  passed the limit check silently. Callers must ask this first:
  un-checkable is not within limits."
  [{:keys [settlement-amount authorized-amount]}]
  (boolean (and (number? settlement-amount) (number? authorized-amount))))

(defn register-settlement-finalization
  "Validate + construct the SETTLEMENT-FINALIZATION registration
  DRAFT -- the processor's own legal act of finalizing a real
  transaction's settlement. Pure function -- does not touch any real
  card-processing system, and never a raw PAN; it builds the RECORD a
  processor would keep. `card.governor` independently re-verifies the
  transaction's own settlement-amount sufficiency against its own
  authorized amount, and blocks a double-settlement of the same
  transaction, before this is ever allowed to commit."
  [transaction-id jurisdiction sequence]
  (when-not (and transaction-id (not= transaction-id ""))
    (throw (ex-info "settlement-finalization: transaction_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "settlement-finalization: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "settlement-finalization: sequence must be >= 0" {})))
  (let [settlement-number (str (str/upper-case jurisdiction) "-STL-" (zero-pad sequence 6))
        record {"record_id" settlement-number
                "kind" "settlement-finalization-draft"
                "transaction_id" transaction-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "settlement_number" settlement-number
     "certificate" (unsigned-certificate "SettlementFinalization" settlement-number settlement-number)}))

(defn register-chargeback-release
  "Validate + construct the CHARGEBACK-RELEASE registration DRAFT --
  the processor's own legal act of releasing a real chargeback hold on
  a transaction. Pure function -- does not touch any real card-
  processing system; it builds the RECORD a processor would keep.
  `card.governor` independently re-verifies the transaction's own
  fraud-flag resolution status, and blocks a double-release of the
  same transaction's chargeback hold, before this is ever allowed to
  commit."
  [transaction-id jurisdiction sequence]
  (when-not (and transaction-id (not= transaction-id ""))
    (throw (ex-info "chargeback-release: transaction_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "chargeback-release: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "chargeback-release: sequence must be >= 0" {})))
  (let [release-number (str (str/upper-case jurisdiction) "-CBR-" (zero-pad sequence 6))
        record {"record_id" release-number
                "kind" "chargeback-release-draft"
                "transaction_id" transaction-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "release_number" release-number
     "certificate" (unsigned-certificate "ChargebackRelease" release-number release-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
