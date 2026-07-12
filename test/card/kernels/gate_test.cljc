(ns card.kernels.gate-test
  "The safety kernel's executable spec, three ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. unknown, all governor dispositions). The façade
     delegates, so this is the guard that delegation didn't change
     semantics.
  3. governor boundary — the confidence floor boundary, the
     fail-closed treatment of out-of-range confidence, and the
     settlement-amount/authorized-amount over-charge boundary,
     exercised through the real `card.governor/check` façade."
  (:require [card.facts :as facts]
            [card.governor :as governor]
            [card.kernels.gate :as gate]
            [card.store :as store]
            [clojure.test :refer [deftest is testing]]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

;; NOTE: unlike 6492's DTI 0.43 / 6491's coverage 1.0, this actor's
;; amount ceiling is NOT a fixed constant — it is the transaction's own
;; :authorized-amount, so there is no second constant-drift pin here;
;; the boundary tests below pin the comparison itself instead.

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change. Op 0 is
;; the fleet-wide read code (this actor's read-ops is empty; the
;; façade never emits it) and op 6 the unknown-write code.

(def ^:private ref-read-ops #{0})
(def ^:private ref-phases
  {0 {:writes #{}            :auto #{}}
   1 {:writes #{1}           :auto #{}}
   2 {:writes #{1 2 3}       :auto #{}}
   3 {:writes #{1 2 3 4 5}   :auto #{1}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (189 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5 6]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest settlement-and-chargeback-auto-enabled-nowhere
  (testing "op 4 (:settlement/finalize), op 5 (:chargeback/release) and op 3
            (:fraud/screen) are auto-enabled at NO phase — kernel restates the
            phase table's permanent structural invariants"
    (doseq [phase [-1 0 1 2 3 4 7]]
      (is (= 0 (gate/op-auto-enabled phase 4)))
      (is (= 0 (gate/op-auto-enabled phase 5)))
      (is (= 0 (gate/op-auto-enabled phase 3))))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :transaction/intake
;; touches neither the store nor the evidence/amount/settled/released
;; checks, so the verdict is decided purely by confidence/actuation —
;; nil store is safe (the unconditional fraud check only consults the
;; store for screen/release ops).

(defn- verdict [proposal]
  (governor/check {:op :transaction/intake :subject "transaction-x"} {} proposal nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

;; ---------------------------------------------------------------
;; Over-charge ceiling boundary through the real façade — the kernel
;; decides in exact integers (authorized < settlement), the façade
;; still produces the human-readable violation map, and both must
;; agree at the boundary. The transaction is fully assessed (evidence
;; satisfied) and clean otherwise, so the verdict isolates the amount
;; check.

(defn- settle-verdict [settlement-amount authorized-amount]
  (let [st (store/with-transactions
             (store/seed-db)
             {"transaction-x" {:id "transaction-x" :merchant-name "n"
                               :jurisdiction "JPN"
                               :settlement-amount settlement-amount
                               :authorized-amount authorized-amount
                               :fraud-flag? false :settled? false
                               :chargeback-released? false :status :intake}})]
    (store/commit-record! st {:effect :assessment/set :path ["transaction-x"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/evidence-checklist "JPN")}})
    (governor/check {:op :settlement/finalize :subject "transaction-x"} {}
                    {:confidence 0.9 :cites ["JPN-spec"]} st)))

(deftest amount-ceiling-boundary-through-facade
  (testing "settlement exactly at the authorized amount clears (strict >)"
    (let [v (settle-verdict 5000 5000)]
      (is (true? (:ok? v)))
      (is (false? (:hard? v)))
      (is (empty? (:violations v)))))
  (testing "one currency unit over the authorization hard-holds, kernel and
            violation map agreeing"
    (let [v (settle-verdict 5001 5000)]
      (is (true? (:hard? v)))
      (is (false? (:ok? v)))
      (is (some #{:settlement-amount-exceeds-authorized}
                (mapv :rule (:violations v))))))
  (testing "one currency unit under the authorization clears"
    (is (true? (:ok? (settle-verdict 4999 5000))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/settle-transaction}))))
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/release-chargeback}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :jurisdiction/assess :subject "transaction-x"} {}
                            {:confidence 0.99 :stake :actuation/settle-transaction :cites []} nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:no-spec-basis} (mapv :rule (:violations v)))))))
