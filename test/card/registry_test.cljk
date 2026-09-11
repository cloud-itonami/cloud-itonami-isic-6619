(ns card.registry-test
  (:require [clojure.test :refer [deftest is]]
            [card.registry :as r]))

;; ----------------------------- settlement-amount-exceeds-authorized? -----------------------------

(deftest not-exceeded-when-at-or-below-authorized
  (is (not (r/settlement-amount-exceeds-authorized? {:settlement-amount 5000 :authorized-amount 5000})))
  (is (not (r/settlement-amount-exceeds-authorized? {:settlement-amount 4000 :authorized-amount 5000}))))

(deftest exceeded-when-over-authorized
  (is (r/settlement-amount-exceeds-authorized? {:settlement-amount 5001 :authorized-amount 5000}))
  (is (r/settlement-amount-exceeds-authorized? {:settlement-amount 6000 :authorized-amount 5000})))

(deftest exceeds-is-false-on-missing-fields
  (is (not (r/settlement-amount-exceeds-authorized? {})))
  (is (not (r/settlement-amount-exceeds-authorized? {:settlement-amount 6000}))))

;; ----------------------------- register-settlement-finalization -----------------------------

(deftest settlement-is-a-draft-not-a-real-settlement
  (let [result (r/register-settlement-finalization "transaction-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest settlement-assigns-settlement-number
  (let [result (r/register-settlement-finalization "transaction-1" "JPN" 7)]
    (is (= (get result "settlement_number") "JPN-STL-000007"))
    (is (= (get-in result ["record" "transaction_id"]) "transaction-1"))
    (is (= (get-in result ["record" "kind"]) "settlement-finalization-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest settlement-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-settlement-finalization "" "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-settlement-finalization "transaction-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-settlement-finalization "transaction-1" "JPN" -1))))

;; ----------------------------- register-chargeback-release -----------------------------

(deftest chargeback-release-is-a-draft-not-a-real-release
  (let [result (r/register-chargeback-release "transaction-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest chargeback-release-assigns-release-number
  (let [result (r/register-chargeback-release "transaction-1" "JPN" 3)]
    (is (= (get result "release_number") "JPN-CBR-000003"))
    (is (= (get-in result ["record" "transaction_id"]) "transaction-1"))
    (is (= (get-in result ["record" "kind"]) "chargeback-release-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest chargeback-release-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-chargeback-release "" "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-chargeback-release "transaction-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-chargeback-release "transaction-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-settlement-finalization "transaction-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-settlement-finalization "transaction-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-STL-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-STL-000001" (get-in hist2 [1 "record_id"])))))
