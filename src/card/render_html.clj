(ns card.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo. It replaces a
  HAND-WRITTEN sample page that this actor's generator had never
  produced and could not have produced: the committed file showed a
  `PAN validation` table (`•••• 1111`), `ISO 8583 messages` and
  `Authorization decisions` — three concepts that appear NOWHERE in
  `src/card/`. `card.store`'s own docstring states the opposite of that
  page: 'This store deliberately carries NO raw PAN (Primary Account
  Number) field at all … there is simply no such field in the schema.'
  Every row below is now read back out of a REAL run.

  ## What actually runs

  `run-demo!` drives the REAL actor stack — `card.operation` (a
  langgraph-clj StateGraph) -> `card.cardadvisor` (proposal) ->
  `card.governor` (independent censor) -> `card.phase` (rollout gate)
  -> `card.store` (SSoT + append-only ledger) — through `langgraph.
  graph/run*`, the same entry point `card.sim` uses. Nothing on the
  page is typed by hand: transactions come from `store/all-
  transactions`, holds from the `:governor-hold` facts the governor
  itself wrote, registers from `store/settlement-history` /
  `store/chargeback-history`, the phase matrix from the `card.phase/
  phases` var, and jurisdiction coverage from `card.facts/coverage`.

  ## Why this scenario

  It walks `transaction-1` through a complete dual-actuation lifecycle
  (intake -> jurisdiction assessment -> fraud screening -> settlement
  finalization -> chargeback release, the last four all human-approved),
  and then exercises ALL SIX of the Card Settlement Governor's HARD
  checks, none of which ever reaches a human:

    1. `:no-spec-basis`                       transaction-2, jurisdiction ATL,
                                              absent from `card.facts/catalog`
    2. `:evidence-incomplete`                 transaction-2 again — because its
                                              assessment was HELD at (1), a
                                              settlement attempt has no evidence
                                              checklist on file. A real causal
                                              chain between two holds, not two
                                              unrelated fixtures.
    3. `:settlement-amount-exceeds-authorized` transaction-3, 6000 settled
                                              against 5000 authorized
    4. `:fraud-flag-unresolved`               transaction-4, screened DIRECTLY
                                              via `:fraud/screen` (never via an
                                              actuation op against an unscreened
                                              transaction — see `card.governor`)
    5. `:already-settled`                     transaction-1 settled twice
    6. `:already-released`                    transaction-1 released twice

  `card.sim` reaches five of these; `:evidence-incomplete` is added
  here so the console covers the governor's whole HARD surface.

  ## Honesty

  Where a value cannot be sourced from the run it is NOT invented.
  `approver-attribution` re-derives, at render time, which of this
  store's registers actually retained the human approver's id, by
  walking the real post-run register contents and looking for approver
  keys — rather than asserting a defect in prose that would go stale
  the day it is fixed. Where the approver is absent from a register,
  the page joins it from the run's own audit facts and labels it as
  audit-only.

  ## Determinism

  No clock, no RNG, no network anywhere on the path: the advisor is a
  deterministic mock, the registry's reference numbers are
  jurisdiction-scoped zero-padded sequences, and every map/set iterated
  for the page is sorted before rendering. Two consecutive runs produce
  a byte-identical file.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [card.facts :as facts]
            [card.governor :as governor]
            [card.operation :as op]
            [card.phase :as phase]
            [card.store :as store]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :processor-officer :phase 3})

;; ----------------------------- the real run -----------------------------

(defn- exec!
  "One real actor run. Returns the run record the page is rendered from
  -- the request as sent, and the graph's own final state."
  [actor tid request]
  (let [r (g/run* actor {:request request :context operator} {:thread-id tid})]
    {:thread tid :request request :state (:state r)}))

(defn- approve!
  "A human processor officer resumes the interrupted thread. Returns the
  same run-record shape, so the page never has to special-case it."
  [actor tid request]
  (let [r (g/run* actor {:approval {:status :approved :by "op-1"}}
                  {:thread-id tid :resume? true})]
    {:thread tid :request request :state (:state r) :resumed? true}))

(defn run-demo!
  "Runs a fresh seeded store through the scenario described in the ns
  docstring. Returns `{:db <store> :runs [<run-record> ...]}` -- `:runs`
  is the ordered list of what the actor was actually asked to do and
  what it actually decided, and `:db` is the SSoT as the actor left it."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])
        step! (fn [tid request]
                (let [r (exec! actor tid request)]
                  (swap! runs conj r)
                  r))
        ok!   (fn [tid request]
                (step! tid request)
                (swap! runs conj (approve! actor tid request)))]

    ;; -- transaction-1: a complete dual-actuation lifecycle --
    (step! "t1-intake" {:op :transaction/intake :subject "transaction-1"
                        :patch {:id "transaction-1" :merchant-name "Sakura Books"}})
    (ok!   "t1-assess" {:op :jurisdiction/assess :subject "transaction-1"})
    (ok!   "t1-fraud"  {:op :fraud/screen :subject "transaction-1"})
    (ok!   "t1-settle" {:op :settlement/finalize :subject "transaction-1"})
    (ok!   "t1-release" {:op :chargeback/release :subject "transaction-1"})

    ;; -- HARD 1: a jurisdiction with no official spec-basis --
    (step! "t2-assess" {:op :jurisdiction/assess :subject "transaction-2" :no-spec? true})
    ;; -- HARD 2: ...so its settlement has no evidence checklist on file --
    (step! "t2-settle" {:op :settlement/finalize :subject "transaction-2"})

    ;; -- HARD 3: settlement amount over-charges its own authorization --
    (ok!   "t3-assess" {:op :jurisdiction/assess :subject "transaction-3"})
    (step! "t3-settle" {:op :settlement/finalize :subject "transaction-3"})

    ;; -- HARD 4: an unresolved fraud flag, found by the screening itself --
    (step! "t4-fraud"  {:op :fraud/screen :subject "transaction-4"})

    ;; -- HARD 5/6: double settlement, double chargeback release --
    (step! "t1-settle-again"  {:op :settlement/finalize :subject "transaction-1"})
    (step! "t1-release-again" {:op :chargeback/release :subject "transaction-1"})

    {:db db :runs @runs}))

;; ----------------------------- derivation -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-str [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- fact-of [audit t] (last (filter #(= t (:t %)) audit)))

(defn- holds
  "The HARD `:governor-hold` facts this run actually wrote to the
  append-only ledger. Not a literal -- the ledger is the evidence."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- outcome
  "Classify one real run from its own audit trail and final
  disposition. Never from a hand-written table."
  [{:keys [state]}]
  (let [audit (:audit state)
        hold  (fact-of audit :governor-hold)]
    (cond
      hold {:kind :hard-hold :violations (:violations hold)}

      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}

      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

      (= :commit (:disposition state)) {:kind :auto-commit}
      :else {:kind :other})))

(defn- outcome-cell [o]
  (case (:kind o)
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; "
                    (esc (str/join ", " (map (comp kw-str :rule) (:violations o))))
                    "</span>")
    :approved (str "<span class=\"ok\">escalated (" (esc (kw-str (:reason o)))
                   ") &rarr; approved by " (esc (:by o)) "</span>")
    :awaiting (str "<span class=\"warn\">awaiting human approval &middot; "
                   (esc (kw-str (:reason o))) "</span>")
    :auto-commit "<span class=\"ok\">auto-commit (governor-clean, phase 3)</span>"
    "<span class=\"muted\">in progress</span>"))

;; -- approver attribution: MEASURED at render time, never asserted --

(defn- deep-key-names
  "Every key name appearing anywhere in `x`, at any depth, as strings --
  so a register keyed by strings (the registry's JSON-ish drafts) and
  one keyed by keywords (the assessment/fraud-screen payloads) are
  scanned the same way."
  [x]
  (cond
    (map? x) (into (into #{} (map #(if (keyword? %) (kw-str %) (str %)) (keys x)))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    (set? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(def ^:private approver-key?
  #(contains? #{"approved-by" "approved_by" "approver" "approved-by-id" "approved_by_id"}
              (str/lower-case %)))

(defn- registers
  "The named registers this store actually exposes through its `Store`
  protocol, each paired with its real post-run contents. `assessment-
  of` / `fraud-screen-of` are per-transaction lookups, so they are
  gathered over the real transaction directory."
  [db]
  (let [ts (store/all-transactions db)]
    [["transaction directory" "store/all-transactions" (vec ts)]
     ["jurisdiction assessments" "store/assessment-of"
      (vec (keep #(store/assessment-of db (:id %)) ts))]
     ["fraud screenings" "store/fraud-screen-of"
      (vec (keep #(store/fraud-screen-of db (:id %)) ts))]
     ["settlement-finalization drafts" "store/settlement-history"
      (vec (store/settlement-history db))]
     ["chargeback-release drafts" "store/chargeback-history"
      (vec (store/chargeback-history db))]
     ["audit ledger" "store/ledger" (vec (store/ledger db))]]))

(defn- approver-attribution
  "DERIVED disclosure: where did the human approver's id actually end up?

  `card.operation`'s `:request-approval` node attaches the approver at
  `[:payload :approved-by]` on the commit record. Whether that survives
  is a per-effect property of `card.store/commit-record!`, NOT a
  fleet-wide constant -- so this walks each real register after the run
  and reports what is measurably there. If the store is changed, this
  section changes with it; nothing here is asserted in prose.

  Returns `{:granted [..] :registers [{:name :fn :n :retains?} ..]}`."
  [db runs]
  {:granted (vec (sort (into #{} (keep #(:by (fact-of (:audit (:state %)) :approval-granted)))
                             runs)))
   :registers (mapv (fn [[nm f contents]]
                      {:name nm :fn f :n (count contents)
                       :retains? (boolean (some approver-key? (deep-key-names contents)))})
                    (registers db))})

;; ----------------------------- rows -----------------------------

(defn- yn [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"muted\">no</span>"))

(defn- transaction-row
  [{:keys [id merchant-name jurisdiction settlement-amount authorized-amount
           fraud-flag? settled? chargeback-released? settlement-number release-number]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td class=\"amt\">%s</td><td class=\"amt\">%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td></tr>")
          (esc id) (esc merchant-name) (esc jurisdiction)
          (esc settlement-amount) (esc authorized-amount)
          (if fraud-flag?
            "<span class=\"critical\">flagged</span>"
            "<span class=\"muted\">clear</span>")
          (if settled?
            (str "<span class=\"ok\">settled &middot; <code>" (esc settlement-number) "</code></span>")
            "<span class=\"muted\">not settled</span>")
          (if chargeback-released?
            (str "<span class=\"ok\">released &middot; <code>" (esc release-number) "</code></span>")
            "<span class=\"muted\">held</span>")))

(defn- run-row [{:keys [thread request resumed?] :as run}]
  (let [o (outcome run)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc thread) (esc (kw-str (:op request))) (esc (:subject request))
            (if resumed? "<span class=\"muted\">human resume</span>" "<span class=\"muted\">actor run</span>")
            (outcome-cell o))))

(defn- hold-row [{:keys [op subject violations confidence]}]
  (let [v (first violations)]
    (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
                 "<td><code>%s</code></td><td>%s</td><td class=\"amt\">%s</td></tr>")
            (esc (kw-str (:rule v))) (esc (kw-str op)) (esc subject)
            (esc (:detail v)) (esc confidence))))

(defn- phase-row
  "One row of the REAL `card.phase/phases` table -- writes and auto
  rights for this op at each phase, read straight off the var."
  [op]
  (let [cell (fn [p]
               (let [{:keys [writes auto]} (get phase/phases p)]
                 (cond
                   (contains? auto op)   "<span class=\"ok\">auto</span>"
                   (contains? writes op) "<span class=\"warn\">approval</span>"
                   :else                 "<span class=\"muted\">&mdash;</span>")))]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc (kw-str op)) (cell 0) (cell 1) (cell 2) (cell 3))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str t)) (esc (kw-str (or op :n-a))) (esc subject)
          (esc (kw-str (or disposition "")))
          (esc (str/join " / " (map #(if (keyword? %) (kw-str %) (str %)) (or basis []))))))

(defn- draft-row [r]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (get r "record_id")) (esc (get r "kind")) (esc (get r "transaction_id"))
          (esc (get r "jurisdiction")) (yn (get r "immutable"))))

(defn- jurisdiction-row [iso3]
  (let [{:keys [name owner-authority legal-basis provenance required-evidence]}
        (facts/spec-basis iso3)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td class=\"amt\">%s</td></tr>"
            (esc iso3) (esc name) (esc owner-authority) (esc legal-basis)
            (esc (count required-evidence)))))

(defn- attribution-row [{:keys [name fn n retains?]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td class=\"amt\">%s</td><td>%s</td></tr>"
          (esc name) (esc fn) (esc n)
          (if retains?
            "<span class=\"ok\">approver id present in the stored record</span>"
            "<span class=\"warn\">not retained &middot; audit only</span>")))

;; ----------------------------- render -----------------------------

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the whole document from `{:db :runs}` produced by
  `run-demo!` (or any other REAL scenario against this actor)."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        hs     (holds db)
        attr   (approver-attribution db runs)
        cov    (facts/coverage)
        n-approved (count (filter #(= :approved (:kind (outcome %))) runs))]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-6619 &middot; card transaction processing &mdash; Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "\n.amt { font-variant-numeric: tabular-nums; text-align: right; }"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Card transaction processing &amp; settlement (ISIC 6619) — Operator Console</h1>\n"
     "  <span class=\"badge\">build-time sample · governor-gated · settlement finalization &amp; chargeback release always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p>Generated at build time by <code>card.render-html</code> (<code>clojure -M:dev:render-html</code>) by actually running this repo's actor: "
     "<code>card.operation</code> (a langgraph-clj StateGraph) &rarr; <code>card.cardadvisor</code> &rarr; <code>card.governor</code> &rarr; "
     "<code>card.phase</code> &rarr; <code>card.store</code>. Every number below was read back out of that run — "
     (esc (count runs)) " actor runs and human resumes, " (esc (count ledger)) " append-only ledger facts, "
     (esc (count hs)) " un-overridable HARD holds, " (esc n-approved) " human approvals.</p>\n"
     "    <p class=\"muted\">This actor never touches a raw PAN: <code>card.store</code>'s schema has no such field at all, so there is nothing to mask. "
     "Settlement and chargeback records produced here are UNSIGNED drafts — signing is the processor's own act, not this actor's.</p>\n"
     "  </section>\n"

     (section
      "Transactions (SSoT after the run)"
      "Read from <code>card.store/all-transactions</code> after the scenario finished — including the settlement and chargeback-release reference numbers the registry actually minted."
      ["Transaction" "Merchant" "Jurisdiction" "Settlement" "Authorized" "Fraud flag" "Settlement status" "Chargeback hold"]
      (map transaction-row (store/all-transactions db)))

     (section
      "Operation runs (this scenario)"
      "One row per <code>langgraph.graph/run*</code> call. The outcome column is classified from each run's own audit channel — a hold is shown because the governor wrote a <code>:governor-hold</code> fact, not because this table says so."
      ["Thread" "Op" "Subject" "Kind" "Outcome"]
      (map run-row runs))

     (section
      "HARD governor holds — all six checks, none reached a human"
      "These are the <code>:governor-hold</code> facts on the append-only ledger. A HARD violation cannot be approved past: <code>card.phase/gate</code> keeps a governor hold as a hold at every phase, so the run never reaches the <code>:request-approval</code> interrupt."
      ["Rule" "Op" "Subject" "Detail (from the governor)" "Advisor confidence"]
      (map hold-row hs))

     (section
      "Rollout phase gate"
      "Read directly off the <code>card.phase/phases</code> var. <code>:settlement/finalize</code> and <code>:chargeback/release</code> are absent from every phase's <code>:auto</code> set — a permanent structural fact, not a milestone still to come. <code>card.governor</code>'s high-stakes set enforces the same invariant independently."
      ["Op" "Phase 0 read-only" "Phase 1 assisted-intake" "Phase 2 assisted-assess" "Phase 3 supervised-auto"]
      (map phase-row (sort-by kw-str phase/write-ops)))

     (section
      "Audit ledger (append-only, this run)"
      "Every commit and every hold, in the order the actor wrote them. This is the evidence an operator needs if a settlement or a chargeback release is later disputed."
      ["Fact" "Op" "Subject" "Disposition" "Basis"]
      (map ledger-row ledger))

     (section
      "Settlement-finalization drafts"
      "From <code>card.store/settlement-history</code>. Jurisdiction-scoped zero-padded sequence numbers minted by <code>card.registry</code>; the accompanying verifiable credential is issued <code>draft-unsigned</code>."
      ["Reference" "Kind" "Transaction" "Jurisdiction" "Immutable"]
      (map draft-row (store/settlement-history db)))

     (section
      "Chargeback-release drafts"
      "From <code>card.store/chargeback-history</code>. Same discipline as above, on a separate sequence counter and a separate double-actuation guard (<code>:chargeback-released?</code>, never a <code>:status</code> value)."
      ["Reference" "Kind" "Transaction" "Jurisdiction" "Immutable"]
      (map draft-row (store/chargeback-history db)))

     (section
      (str "Jurisdiction spec-basis coverage &mdash; " (esc (:covered cov)) " of "
           (esc (:requested cov)) " seeded")
      (str "From <code>card.facts/coverage</code>. " (esc (:note cov))
           " A jurisdiction absent from this catalog has NO spec-basis, and the governor holds any proposal that tries to invent one — which is exactly what happened to <code>transaction-2</code> (ATL) above.")
      ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Required evidence"]
      (map jurisdiction-row (:covered-jurisdictions cov)))

     ;; -- the honest disclosure, derived, not asserted --
     "  <section class=\"card\">\n"
     "    <h2>Approver attribution — where the human approver's id actually survives</h2>\n"
     "    <p class=\"muted\">This section is <strong>measured at render time</strong>, not asserted. "
     "The scenario granted " (esc (count (:granted attr))) " distinct approver id"
     (if (= 1 (count (:granted attr))) "" "s") " ("
     (str/join ", " (map #(str "<code>" (esc %) "</code>") (:granted attr)))
     ") across " (esc n-approved) " approvals. Each register below was then walked for an approver key at any depth. "
     "Whether the id survives is a per-effect property of <code>card.store/commit-record!</code>: the branches that persist the advisor's "
     "<code>:payload</code> keep it, the branches that rebuild their record from <code>card.registry</code> do not. "
     "If the store changes, this table changes with it.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Register</th><th>Source</th><th>Records</th><th>Approver id retained?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map attribution-row (:registers attr))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">Where a register shows <em>not retained</em>, the approver is not missing from the world — it is in this run's "
     "<code>:approval-granted</code> audit facts, joined into the &ldquo;Operation runs&rdquo; table above and labelled there. "
     "It is simply <strong>not part of the stored record</strong>, so a later query over the SSoT alone could not answer "
     "&ldquo;who approved this settlement?&rdquo;. Saying nothing here would make &ldquo;nobody approved it&rdquo; and "
     "&ldquo;the store dropped the approver&rdquo; look identical.</p>\n"
     "  </section>\n"

     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a
    ;; governor -- it is a screenshot of an actor nobody censored. Make
    ;; the requirement a build-time invariant, not a convention.
    (when (empty? hs)
      (throw (ex-info (str "no :governor-hold fact on the ledger — refusing to write a console "
                           "that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :runs (count (:runs result))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (:runs result)) " runs, "
                  (count (store/settlement-history db)) " settlement drafts, "
                  (count (store/chargeback-history db)) " chargeback drafts)"))))
