(ns card.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:settlement/finalize`/`:chargeback/release` must NEVER
  be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [card.phase :as phase]))

(deftest settlement-finalize-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real settlement finalization"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :settlement/finalize))
          (str "phase " n " must not auto-commit :settlement/finalize")))))

(deftest chargeback-release-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real chargeback-hold release"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :chargeback/release))
          (str "phase " n " must not auto-commit :chargeback/release")))))

(deftest fraud-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :fraud/screen))
          (str "phase " n " must not auto-commit :fraud/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":transaction/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:transaction/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :transaction/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :settlement/finalize} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :chargeback/release} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :transaction/intake} :commit)))))
