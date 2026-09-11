(ns card.store
  "SSoT for the card actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam every prior `cloud-
  itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/card/store_contract_test.clj), which is the whole point: the
  actor, the Card Settlement Governor and the audit ledger never know
  which SSoT they run on.

  Like `hospital.store`'s dual treatment/discharge history and every
  other dual-actuation sibling before it, this actor has TWO actuation
  events (finalizing a settlement, releasing a chargeback hold) acting
  on the SAME entity (a transaction), each with its OWN history
  collection, sequence counter and dedicated double-actuation-guard
  boolean (`:settled?`/`:chargeback-released?`, never a `:status`
  value) -- the same discipline every prior sibling governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320).

  This store deliberately carries NO raw PAN (Primary Account Number)
  field at all -- see README `Actuation`/`Scope`: tokenization at the
  edge is the operator's own responsibility, entirely out of scope for
  this actor, so there is no PAN field to omit-by-convention, there is
  simply no such field in the schema.

  The ledger stays append-only on every backend: 'which transaction
  was screened for an unresolved fraud flag, which settlement was
  finalized, which chargeback hold was released, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a merchant/cardholder trusting a
  processor needs, and the evidence an operator needs if a settlement
  or chargeback release is later disputed."
  (:require [card.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (transaction [s id])
  (all-transactions [s])
  (fraud-screen-of [s transaction-id] "committed fraud screening verdict for a transaction, or nil")
  (assessment-of [s transaction-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (settlement-history [s] "the append-only settlement-finalization history (card.registry drafts)")
  (chargeback-history [s] "the append-only chargeback-release history (card.registry drafts)")
  (next-settlement-sequence [s jurisdiction] "next settlement-number sequence for a jurisdiction")
  (next-chargeback-sequence [s jurisdiction] "next chargeback-release-number sequence for a jurisdiction")
  (transaction-already-settled? [s transaction-id] "has this transaction already been settled?")
  (transaction-already-released? [s transaction-id] "has this transaction's chargeback hold already been released?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-transactions [s transactions] "replace/seed the transaction directory (map id->transaction)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained transaction set covering both actuation
  lifecycles (finalizing a settlement, releasing a chargeback hold) so
  the actor + tests run offline. No raw PAN field exists anywhere in
  this data -- see ns docstring."
  []
  {:transactions
   {"transaction-1" {:id "transaction-1" :merchant-name "Sakura Books"
                     :settlement-amount 5000 :authorized-amount 5000 :fraud-flag? false
                     :settled? false :chargeback-released? false
                     :jurisdiction "JPN" :status :intake}
    "transaction-2" {:id "transaction-2" :merchant-name "Atlantis Goods"
                     :settlement-amount 5000 :authorized-amount 5000 :fraud-flag? false
                     :settled? false :chargeback-released? false
                     :jurisdiction "ATL" :status :intake}
    "transaction-3" {:id "transaction-3" :merchant-name "鈴木商店"
                     :settlement-amount 6000 :authorized-amount 5000 :fraud-flag? false
                     :settled? false :chargeback-released? false
                     :jurisdiction "JPN" :status :intake}
    "transaction-4" {:id "transaction-4" :merchant-name "田中商会"
                     :settlement-amount 5000 :authorized-amount 5000 :fraud-flag? true
                     :settled? false :chargeback-released? false
                     :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-settlement!
  "Backend-agnostic `:transaction/mark-settled` -- looks up the
  transaction via the protocol and drafts the settlement-finalization
  record, and returns {:result .. :transaction-patch ..} for the
  caller to persist."
  [s transaction-id]
  (let [t (transaction s transaction-id)
        seq-n (next-settlement-sequence s (:jurisdiction t))
        result (registry/register-settlement-finalization transaction-id (:jurisdiction t) seq-n)]
    {:result result
     :transaction-patch {:settled? true
                         :settlement-number (get result "settlement_number")}}))

(defn- release-chargeback-hold!
  "Backend-agnostic `:transaction/mark-released` -- looks up the
  transaction via the protocol and drafts the chargeback-release
  record, and returns {:result .. :transaction-patch ..} for the
  caller to persist."
  [s transaction-id]
  (let [t (transaction s transaction-id)
        seq-n (next-chargeback-sequence s (:jurisdiction t))
        result (registry/register-chargeback-release transaction-id (:jurisdiction t) seq-n)]
    {:result result
     :transaction-patch {:chargeback-released? true
                         :release-number (get result "release_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (transaction [_ id] (get-in @a [:transactions id]))
  (all-transactions [_] (sort-by :id (vals (:transactions @a))))
  (fraud-screen-of [_ id] (get-in @a [:fraud-screens id]))
  (assessment-of [_ transaction-id] (get-in @a [:assessments transaction-id]))
  (ledger [_] (:ledger @a))
  (settlement-history [_] (:settlements @a))
  (chargeback-history [_] (:chargeback-releases @a))
  (next-settlement-sequence [_ jurisdiction] (get-in @a [:settlement-sequences jurisdiction] 0))
  (next-chargeback-sequence [_ jurisdiction] (get-in @a [:chargeback-sequences jurisdiction] 0))
  (transaction-already-settled? [_ transaction-id] (boolean (get-in @a [:transactions transaction-id :settled?])))
  (transaction-already-released? [_ transaction-id] (boolean (get-in @a [:transactions transaction-id :chargeback-released?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :transaction/upsert
      (swap! a update-in [:transactions (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :fraud-screen/set
      (swap! a assoc-in [:fraud-screens (first path)] payload)

      :transaction/mark-settled
      (let [transaction-id (first path)
            {:keys [result transaction-patch]} (finalize-settlement! s transaction-id)
            jurisdiction (:jurisdiction (transaction s transaction-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:settlement-sequences jurisdiction] (fnil inc 0))
                       (update-in [:transactions transaction-id] merge transaction-patch)
                       (update :settlements registry/append result))))
        result)

      :transaction/mark-released
      (let [transaction-id (first path)
            {:keys [result transaction-patch]} (release-chargeback-hold! s transaction-id)
            jurisdiction (:jurisdiction (transaction s transaction-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:chargeback-sequences jurisdiction] (fnil inc 0))
                       (update-in [:transactions transaction-id] merge transaction-patch)
                       (update :chargeback-releases registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-transactions [s transactions] (when (seq transactions) (swap! a assoc :transactions transactions)) s))

(defn seed-db
  "A MemStore seeded with the demo transaction set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :fraud-screens {} :ledger [] :settlement-sequences {}
                           :settlements [] :chargeback-sequences {} :chargeback-releases []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/fraud-screen payloads, ledger facts,
  settlement/chargeback records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:transaction/id                     {:db/unique :db.unique/identity}
   :assessment/transaction-id          {:db/unique :db.unique/identity}
   :fraud-screen/transaction-id        {:db/unique :db.unique/identity}
   :ledger/seq                         {:db/unique :db.unique/identity}
   :settlement/seq                     {:db/unique :db.unique/identity}
   :chargeback/seq                     {:db/unique :db.unique/identity}
   :settlement-sequence/jurisdiction   {:db/unique :db.unique/identity}
   :chargeback-sequence/jurisdiction   {:db/unique :db.unique/identity}})

(defn- transaction->tx [{:keys [id merchant-name settlement-amount authorized-amount fraud-flag?
                                settled? chargeback-released?
                                jurisdiction status settlement-number release-number]}]
  (cond-> {:transaction/id id}
    merchant-name                        (assoc :transaction/merchant-name merchant-name)
    settlement-amount                    (assoc :transaction/settlement-amount settlement-amount)
    authorized-amount                    (assoc :transaction/authorized-amount authorized-amount)
    (some? fraud-flag?)                  (assoc :transaction/fraud-flag? fraud-flag?)
    (some? settled?)                     (assoc :transaction/settled? settled?)
    (some? chargeback-released?)         (assoc :transaction/chargeback-released? chargeback-released?)
    jurisdiction                        (assoc :transaction/jurisdiction jurisdiction)
    status                              (assoc :transaction/status status)
    settlement-number                    (assoc :transaction/settlement-number settlement-number)
    release-number                      (assoc :transaction/release-number release-number)))

(def ^:private transaction-pull
  [:transaction/id :transaction/merchant-name :transaction/settlement-amount :transaction/authorized-amount
   :transaction/fraud-flag? :transaction/settled? :transaction/chargeback-released?
   :transaction/jurisdiction :transaction/status :transaction/settlement-number :transaction/release-number])

(defn- pull->transaction [m]
  (when (:transaction/id m)
    {:id (:transaction/id m) :merchant-name (:transaction/merchant-name m)
     :settlement-amount (:transaction/settlement-amount m)
     :authorized-amount (:transaction/authorized-amount m)
     :fraud-flag? (boolean (:transaction/fraud-flag? m))
     :settled? (boolean (:transaction/settled? m))
     :chargeback-released? (boolean (:transaction/chargeback-released? m))
     :jurisdiction (:transaction/jurisdiction m) :status (:transaction/status m)
     :settlement-number (:transaction/settlement-number m) :release-number (:transaction/release-number m)}))

(defrecord DatomicStore [conn]
  Store
  (transaction [_ id]
    (pull->transaction (d/pull (d/db conn) transaction-pull [:transaction/id id])))
  (all-transactions [_]
    (->> (d/q '[:find [?id ...] :where [?e :transaction/id ?id]] (d/db conn))
         (map #(pull->transaction (d/pull (d/db conn) transaction-pull [:transaction/id %])))
         (sort-by :id)))
  (fraud-screen-of [_ id]
    (ls/dec* (d/q '[:find ?p . :in $ ?tid
                :where [?k :fraud-screen/transaction-id ?tid] [?k :fraud-screen/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ transaction-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?tid
                :where [?a :assessment/transaction-id ?tid] [?a :assessment/payload ?p]]
              (d/db conn) transaction-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (settlement-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :settlement/seq ?s] [?e :settlement/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (chargeback-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :chargeback/seq ?s] [?e :chargeback/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (next-settlement-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :settlement-sequence/jurisdiction ?j] [?e :settlement-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-chargeback-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :chargeback-sequence/jurisdiction ?j] [?e :chargeback-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (transaction-already-settled? [s transaction-id]
    (boolean (:settled? (transaction s transaction-id))))
  (transaction-already-released? [s transaction-id]
    (boolean (:chargeback-released? (transaction s transaction-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :transaction/upsert
      (d/transact! conn [(transaction->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/transaction-id (first path) :assessment/payload (ls/enc payload)}])

      :fraud-screen/set
      (d/transact! conn [{:fraud-screen/transaction-id (first path) :fraud-screen/payload (ls/enc payload)}])

      :transaction/mark-settled
      (let [transaction-id (first path)
            {:keys [result transaction-patch]} (finalize-settlement! s transaction-id)
            jurisdiction (:jurisdiction (transaction s transaction-id))
            next-n (inc (next-settlement-sequence s jurisdiction))]
        (d/transact! conn
                     [(transaction->tx (assoc transaction-patch :id transaction-id))
                      {:settlement-sequence/jurisdiction jurisdiction :settlement-sequence/next next-n}
                      {:settlement/seq (count (settlement-history s)) :settlement/record (ls/enc (get result "record"))}])
        result)

      :transaction/mark-released
      (let [transaction-id (first path)
            {:keys [result transaction-patch]} (release-chargeback-hold! s transaction-id)
            jurisdiction (:jurisdiction (transaction s transaction-id))
            next-n (inc (next-chargeback-sequence s jurisdiction))]
        (d/transact! conn
                     [(transaction->tx (assoc transaction-patch :id transaction-id))
                      {:chargeback-sequence/jurisdiction jurisdiction :chargeback-sequence/next next-n}
                      {:chargeback/seq (count (chargeback-history s)) :chargeback/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-transactions [s transactions]
    (when (seq transactions) (d/transact! conn (mapv transaction->tx (vals transactions)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:transactions ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [transactions]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-transactions s transactions))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo transaction set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
