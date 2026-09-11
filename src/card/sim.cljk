(ns card.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean transaction
  through intake -> jurisdiction assessment -> fraud screening ->
  settlement-finalization proposal (always escalates) -> human
  approval -> commit, then through chargeback-release proposal
  (always escalates) -> human approval -> commit, then shows four HARD
  holds (a jurisdiction with no spec-basis, a settlement amount over-
  charging its own authorized amount, an unresolved fraud flag
  screened directly via `:fraud/screen` [never via an actuation op
  against an unscreened transaction -- see this actor's own governor
  ns docstring / the lesson `parksafety`'s ADR-2607071922 Decision 5,
  `eldercare`'s, `museum`'s, `conservation`'s, `salon`'s,
  `entertainment`'s, `casework`'s, `hospital`'s, `facility`'s,
  `school`'s, `association`'s, `leasing`'s, `behavioral`'s and
  `secondary`'s ADR-0001s already recorded], and a double settlement/
  chargeback-release of an already-processed transaction) that never
  reach a human at all, and prints the audit ledger + the draft
  settlement-finalization and chargeback-release records."
  (:require [langgraph.graph :as g]
            [card.store :as store]
            [card.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :processor-officer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== transaction/intake transaction-1 (JPN, clean; settlement within authorization, no fraud flag) ==")
    (println (exec! actor "t1" {:op :transaction/intake :subject "transaction-1"
                                :patch {:id "transaction-1" :merchant-name "Sakura Books"}} operator))

    (println "== jurisdiction/assess transaction-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "transaction-1"} operator))
    (println (approve! actor "t2"))

    (println "== fraud/screen transaction-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :fraud/screen :subject "transaction-1"} operator))
    (println (approve! actor "t3"))

    (println "== settlement/finalize transaction-1 (always escalates -- actuation/settle-transaction) ==")
    (let [r (exec! actor "t4" {:op :settlement/finalize :subject "transaction-1"} operator)]
      (println r)
      (println "-- human processor officer approves --")
      (println (approve! actor "t4")))

    (println "== chargeback/release transaction-1 (always escalates -- actuation/release-chargeback) ==")
    (let [r (exec! actor "t5" {:op :chargeback/release :subject "transaction-1"} operator)]
      (println r)
      (println "-- human processor officer approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess transaction-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "transaction-2" :no-spec? true} operator))

    (println "== jurisdiction/assess transaction-3 (escalates -- human approves; sets up the over-charge test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "transaction-3"} operator))
    (println (approve! actor "t7"))

    (println "== settlement/finalize transaction-3 (6000/5000 settlement-vs-authorized -> HARD hold) ==")
    (println (exec! actor "t8" {:op :settlement/finalize :subject "transaction-3"} operator))

    (println "== fraud/screen transaction-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :fraud/screen :subject "transaction-4"} operator))

    (println "== settlement/finalize transaction-1 AGAIN (double-settlement -> HARD hold) ==")
    (println (exec! actor "t10" {:op :settlement/finalize :subject "transaction-1"} operator))

    (println "== chargeback/release transaction-1 AGAIN (double-release -> HARD hold) ==")
    (println (exec! actor "t11" {:op :chargeback/release :subject "transaction-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft settlement-finalization records ==")
    (doseq [r (store/settlement-history db)] (println r))

    (println "== draft chargeback-release records ==")
    (doseq [r (store/chargeback-history db)] (println r))))
