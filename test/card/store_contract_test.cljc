(ns card.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [card.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Books" (:merchant-name (store/transaction s "transaction-1"))))
      (is (= "JPN" (:jurisdiction (store/transaction s "transaction-1"))))
      (is (= 5000 (:settlement-amount (store/transaction s "transaction-1"))))
      (is (= 5000 (:authorized-amount (store/transaction s "transaction-1"))))
      (is (false? (:fraud-flag? (store/transaction s "transaction-1"))))
      (is (= 6000 (:settlement-amount (store/transaction s "transaction-3"))))
      (is (true? (:fraud-flag? (store/transaction s "transaction-4"))))
      (is (false? (:settled? (store/transaction s "transaction-1"))))
      (is (false? (:chargeback-released? (store/transaction s "transaction-1"))))
      (is (= ["transaction-1" "transaction-2" "transaction-3" "transaction-4"]
             (mapv :id (store/all-transactions s))))
      (is (nil? (store/fraud-screen-of s "transaction-1")))
      (is (nil? (store/assessment-of s "transaction-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/settlement-history s)))
      (is (= [] (store/chargeback-history s)))
      (is (zero? (store/next-settlement-sequence s "JPN")))
      (is (zero? (store/next-chargeback-sequence s "JPN")))
      (is (false? (store/transaction-already-settled? s "transaction-1")))
      (is (false? (store/transaction-already-released? s "transaction-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :transaction/upsert
                                 :value {:id "transaction-1" :merchant-name "Sakura Books"}})
        (is (= "Sakura Books" (:merchant-name (store/transaction s "transaction-1"))))
        (is (= 5000 (:settlement-amount (store/transaction s "transaction-1"))) "unrelated field preserved"))
      (testing "assessment / fraud-screen payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["transaction-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "transaction-1")))
        (store/commit-record! s {:effect :fraud-screen/set :path ["transaction-1"]
                                 :payload {:transaction-id "transaction-1" :verdict :resolved}})
        (is (= {:transaction-id "transaction-1" :verdict :resolved} (store/fraud-screen-of s "transaction-1"))))
      (testing "settlement finalization drafts a settlement record and advances the sequence"
        (store/commit-record! s {:effect :transaction/mark-settled :path ["transaction-1"]})
        (is (= "JPN-STL-000000" (get (first (store/settlement-history s)) "record_id")))
        (is (= "settlement-finalization-draft" (get (first (store/settlement-history s)) "kind")))
        (is (true? (:settled? (store/transaction s "transaction-1"))))
        (is (= 1 (count (store/settlement-history s))))
        (is (= 1 (store/next-settlement-sequence s "JPN")))
        (is (true? (store/transaction-already-settled? s "transaction-1")))
        (is (false? (store/transaction-already-settled? s "transaction-2"))))
      (testing "chargeback release drafts a record and advances the sequence"
        (store/commit-record! s {:effect :transaction/mark-released :path ["transaction-1"]})
        (is (= "JPN-CBR-000000" (get (first (store/chargeback-history s)) "record_id")))
        (is (= "chargeback-release-draft" (get (first (store/chargeback-history s)) "kind")))
        (is (true? (:chargeback-released? (store/transaction s "transaction-1"))))
        (is (= 1 (count (store/chargeback-history s))))
        (is (= 1 (store/next-chargeback-sequence s "JPN")))
        (is (true? (store/transaction-already-released? s "transaction-1")))
        (is (false? (store/transaction-already-released? s "transaction-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/transaction s "nope")))
    (is (= [] (store/all-transactions s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/settlement-history s)))
    (is (= [] (store/chargeback-history s)))
    (is (zero? (store/next-settlement-sequence s "JPN")))
    (is (zero? (store/next-chargeback-sequence s "JPN")))
    (store/with-transactions s {"x" {:id "x" :merchant-name "n" :settlement-amount 5000
                                     :authorized-amount 5000 :fraud-flag? false
                                     :settled? false :chargeback-released? false
                                     :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:merchant-name (store/transaction s "x"))))))
