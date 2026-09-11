(ns card.governor-contract-test
  "The governor contract as executable tests -- the card-processing
  analog of `cloud-itonami-isic-6512`'s `casualty.governor-contract-
  test`. The single invariant under test:

    Card Advisor never settles a transaction or releases a chargeback
    hold the Card Settlement Governor would reject, `:settlement/
    finalize`/`:chargeback/release` NEVER auto-commit at any phase,
    `:transaction/intake` (no direct capital risk) MAY auto-commit
    when clean, and every decision (commit OR hold) leaves exactly
    one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [card.store :as store]
            [card.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :processor-officer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through fraud screening -> approve, leaving a
  screening on file. Only safe to call for a transaction whose fraud
  status has already resolved -- an unresolved flag HARD-holds the
  screen itself (see
  `fraud-flag-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :fraud/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :transaction/intake :subject "transaction-1"
                   :patch {:id "transaction-1" :merchant-name "Sakura Books"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Books" (:merchant-name (store/transaction db "transaction-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "transaction-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "transaction-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "transaction-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "transaction-1")) "no assessment written"))))

(deftest settlement-finalize-without-assessment-is-held
  (testing "settlement/finalize before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :settlement/finalize :subject "transaction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest settlement-amount-exceeds-authorized-is-held
  (testing "a transaction whose settlement amount exceeds its own authorized amount -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "transaction-3")
          res (exec-op actor "t5" {:op :settlement/finalize :subject "transaction-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:settlement-amount-exceeds-authorized} (-> (store/ledger db) last :basis)))
      (is (empty? (store/settlement-history db))))))

(deftest fraud-flag-is-held-and-unoverridable
  (testing "an unresolved fraud flag on a transaction -> HOLD, and never reaches request-approval -- exercised via :fraud/screen DIRECTLY, not via the actuation op against an unscreened transaction (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's, conservation's, salon's, entertainment's, casework's, hospital's, facility's, school's, association's, leasing's, behavioral's and secondary's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :fraud/screen :subject "transaction-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:fraud-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/fraud-screen-of db "transaction-4")) "no clearance written"))))

(deftest settlement-finalize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, within-authorization transaction still ALWAYS interrupts for human approval -- actuation/settle-transaction is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "transaction-1")
          r1 (exec-op actor "t7" {:op :settlement/finalize :subject "transaction-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, settlement record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:settled? (store/transaction db "transaction-1"))))
          (is (= 1 (count (store/settlement-history db))) "one draft settlement record"))))))

(deftest chargeback-release-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, resolved-fraud transaction still ALWAYS interrupts for human approval -- actuation/release-chargeback is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "transaction-1")
          _ (screen! actor "t8pre2" "transaction-1")
          r1 (exec-op actor "t8" {:op :chargeback/release :subject "transaction-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, chargeback-release record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:chargeback-released? (store/transaction db "transaction-1"))))
          (is (= 1 (count (store/chargeback-history db))) "one draft chargeback-release record"))))))

(deftest settlement-finalize-double-settlement-is-held
  (testing "settling the same transaction twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "transaction-1")
          _ (exec-op actor "t9a" {:op :settlement/finalize :subject "transaction-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :settlement/finalize :subject "transaction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-settled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/settlement-history db))) "still only the one earlier settlement"))))

(deftest chargeback-release-double-release-is-held
  (testing "releasing the same transaction's chargeback hold twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "transaction-1")
          _ (screen! actor "t10pre2" "transaction-1")
          _ (exec-op actor "t10a" {:op :chargeback/release :subject "transaction-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :chargeback/release :subject "transaction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-released} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/chargeback-history db))) "still only the one earlier release"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :transaction/intake :subject "transaction-1"
                          :patch {:id "transaction-1" :merchant-name "Sakura Books"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "transaction-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
