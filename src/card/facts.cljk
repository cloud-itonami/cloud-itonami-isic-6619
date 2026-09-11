(ns card.facts
  "Per-jurisdiction card-transaction-processing/settlement regulatory
  catalog -- the G2-style spec-basis table the Card Settlement
  Governor checks every jurisdiction/assess proposal against ('did the
  advisor cite an OFFICIAL public source for this jurisdiction's card-
  settlement/chargeback-dispute requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official payment-
  services/consumer-billing regulator (see `:provenance`); they are a
  STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source, done -- never invent a jurisdiction's
  requirements to make coverage look bigger.

  The JPN entry cites the 2016 Installment Sales Act (割賦販売法)
  amendment's credit-card-number-handling business registration
  regime -- the specific provision covering card-data-handling
  processors, not a generic consumer-credit citation. The USA entry
  cites Regulation Z's billing-error/chargeback-dispute-rights
  provisions (the Fair Credit Billing Act's implementing regulation),
  the most domain-specific federal citation for the chargeback side of
  this vertical's scope. PCI DSS (the card networks' own private data-
  security standard) is a real, widely-cited industry requirement but
  is NOT a government regulator -- this catalog cites only official
  public regulators, per this fleet's spec-basis discipline.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  transaction-intake/authorization-disclosure/settlement-ledger/
  chargeback-procedure evidence set submitted in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "経済産業省 (Ministry of Economy, Trade and Industry, METI)"
          :legal-basis "割賦販売法 (Installment Sales Act) -- クレジットカード番号等取扱契約締結事業者の登録制度"
          :national-spec "カード番号等取扱事業者の安全管理措置・決済代行に係る運営基準"
          :provenance "https://www.meti.go.jp/"
          :required-evidence ["取引受付記録 (transaction-intake record)"
                              "オーソリゼーション開示書 (authorization-disclosure document)"
                              "決済元帳証明書 (settlement-ledger certificate)"
                              "チャージバック処理手続き文書 (chargeback-procedure document)"]}
   "USA" {:name "United States"
          :owner-authority "Consumer Financial Protection Bureau (CFPB)"
          :legal-basis "Fair Credit Billing Act / Regulation Z (12 CFR Part 1026, billing-error and chargeback-dispute rights)"
          :national-spec "Billing-error resolution and chargeback-dispute procedural requirements"
          :provenance "https://www.consumerfinance.gov/rules-policy/regulations/1026/"
          :required-evidence ["Transaction-intake record"
                              "Authorization-disclosure document"
                              "Settlement-ledger certificate"
                              "Chargeback-procedure document"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "Payment Services Regulations 2017 (PSD2 transposition)"
          :national-spec "Payment-institution authorization, settlement-finality and dispute-resolution requirements"
          :provenance "https://www.fca.org.uk/"
          :required-evidence ["Transaction-intake record"
                              "Authorization-disclosure document"
                              "Settlement-ledger certificate"
                              "Chargeback-procedure document"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Zahlungsdiensteaufsichtsgesetz (ZAG, Payment Services Supervision Act)"
          :national-spec "Zahlungsabwicklungs- und Rückbuchungsverfahren für Zahlungsinstitute"
          :provenance "https://www.bafin.de/"
          :required-evidence ["Transaktionsaufnahmeprotokoll (transaction-intake record)"
                              "Autorisierungsoffenlegung (authorization-disclosure document)"
                              "Abrechnungsnachweis (settlement-ledger certificate)"
                              "Rückbuchungsverfahrensdokument (chargeback-procedure document)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to finalize a
  settlement or release a chargeback hold on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6619 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `card.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
