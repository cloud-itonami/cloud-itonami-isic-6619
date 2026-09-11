(ns card.cardadvisor
  "Card Advisor client -- the *contained intelligence node* for the
  card-processing actor.

  It normalizes transaction-intake, drafts a per-jurisdiction card-
  settlement/chargeback-dispute evidence checklist, screens
  transactions for an unresolved fraud flag, drafts the settlement-
  finalization action, and drafts the chargeback-release action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real settlement/chargeback-hold release, and
  it NEVER handles a raw PAN (Primary Account Number) -- see README
  `Scope`. Every output is censored downstream by `card.governor`
  before anything touches the SSoT, and `:settlement/finalize`/
  `:chargeback/release` proposals NEVER auto-commit at any phase --
  see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/settle-transaction | :actuation/release-chargeback | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [card.facts :as facts]
            [card.registry :as registry]
            [card.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the transaction, settlement/authorized-amount
  figures or jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "取引記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :transaction/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction card-settlement/chargeback-dispute evidence
  checklist draft. `:no-spec?` injects the failure mode we must defend
  against: proposing a checklist for a jurisdiction with NO official
  spec-basis in `card.facts` -- the Card Settlement Governor must
  reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [t (store/transaction db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction t))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "card.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-fraud
  "Fraud screening draft. `:fraud-flag?` on the transaction record
  injects the failure mode: the Card Settlement Governor must HOLD,
  un-overridably, on any unresolved fraud flag."
  [db {:keys [subject]}]
  (let [t (store/transaction db subject)]
    (cond
      (nil? t)
      {:summary "対象取引記録が見つかりません" :rationale "no transaction record"
       :cites [] :effect :fraud-screen/set :value {:transaction-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:fraud-flag? t))
      {:summary    (str (:merchant-name t) ": 不正利用フラグを検出")
       :rationale  "スクリーニングが未解決の不正利用フラグを検出。人手確認とホールドが必須。"
       :cites      [:fraud-check]
       :effect     :fraud-screen/set
       :value      {:transaction-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:merchant-name t) ": 不正利用フラグなし")
       :rationale  "不正利用スクリーニング完了。"
       :cites      [:fraud-check]
       :effect     :fraud-screen/set
       :value      {:transaction-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-settlement-finalization
  "Draft the actual SETTLEMENT-FINALIZATION action -- finalizing a
  real transaction's settlement. ALWAYS `:stake :actuation/settle-
  transaction` -- this is a REAL-WORLD financial act, never a draft
  the actor may auto-run. See README `Actuation`: no phase ever adds
  this op to a phase's `:auto` set (`card.phase`); the governor also
  always escalates on `:actuation/settle-transaction`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [t (store/transaction db subject)]
    {:summary    (str subject " 向け決済確定提案"
                      (when t (str " (merchant=" (:merchant-name t) ")")))
     :rationale  (if t
                   (str "settlement-amount=" (:settlement-amount t)
                        " authorized-amount=" (:authorized-amount t))
                   "取引記録が見つかりません")
     :cites      (if t [subject] [])
     :effect     :transaction/mark-settled
     :value      {:transaction-id subject}
     :stake      :actuation/settle-transaction
     :confidence (if (and t (not (registry/settlement-amount-exceeds-authorized? t))) 0.9 0.3)}))

(defn- propose-chargeback-release
  "Draft the actual CHARGEBACK-RELEASE action -- releasing a real
  chargeback hold on a transaction. ALWAYS `:stake :actuation/release-
  chargeback` -- this is a REAL-WORLD financial act, never a draft the
  actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`card.phase`); the governor also always
  escalates on `:actuation/release-chargeback`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [t (store/transaction db subject)]
    {:summary    (str subject " 向けチャージバック解除提案"
                      (when t (str " (merchant=" (:merchant-name t) ")")))
     :rationale  (if t
                   "jurisdiction-evidence-checklist referenced"
                   "取引記録が見つかりません")
     :cites      (if t [subject] [])
     :effect     :transaction/mark-released
     :value      {:transaction-id subject}
     :stake      :actuation/release-chargeback
     :confidence (if t 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :transaction/intake    (normalize-intake db request)
    :jurisdiction/assess   (assess-jurisdiction db request)
    :fraud/screen          (screen-fraud db request)
    :settlement/finalize   (propose-settlement-finalization db request)
    :chargeback/release    (propose-chargeback-release db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはカード処理会社の決済確定・チャージバック解除エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。生のカード番号(PAN)は絶対に扱いません。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:transaction/upsert|:assessment/set|:fraud-screen/set|"
       ":transaction/mark-settled|:transaction/mark-released) "
       ":stake(:actuation/settle-transaction か :actuation/release-chargeback か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess   {:transaction (store/transaction st subject)}
    :fraud/screen          {:transaction (store/transaction st subject)}
    :settlement/finalize   {:transaction (store/transaction st subject)}
    :chargeback/release    {:transaction (store/transaction st subject)}
    {:transaction (store/transaction st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Card Settlement Governor
  escalates/holds -- an LLM hiccup can never auto-settle a transaction
  or auto-release a chargeback hold."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :cardadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
