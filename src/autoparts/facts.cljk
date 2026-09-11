(ns autoparts.facts
  "A small, honest catalog of REAL public sources a `:flag-compatibility-
  concern` proposal MAY cite when the concern-type is `:recall` or
  `:compatibility` -- mirrors the 'honesty over coverage' discipline
  every sibling actor's own `facts` namespace establishes (e.g.
  `cloud-itonami-isic-4510`'s `vehiclesale.facts`).

  Unlike the governor's HARD checks (verified-party-gate / propose-only
  / closed-op-allowlist / compatibility-certification-finalization-
  block, see `autoparts.governor`), citation of one of these sources is
  NOT itself a governor-enforced gate in this R0 -- this actor's scope
  is operations COORDINATION (surfacing a concern for a human /
  downstream authority to act on), not adjudicating whether a citation
  is sufficient to resolve a recall or compatibility question. That
  judgment belongs to the manufacturer, a certified inspection body, or
  the relevant regulator -- never this actor (see README `Scope`).
  Extending this catalog is additive: append a real, citable source --
  never fabricate one.")

(def catalog
  "Each entry: {:id :name :class :access :url}."
  [{:id :nhtsa-recalls
    :name "NHTSA vehicle recalls (National Highway Traffic Safety Administration)"
    :class :federal-recall-registry
    :access :public-api
    :url "https://www.nhtsa.gov/recalls"}
   {:id :oem-fitment-guide
    :name "Operator-registered OEM/manufacturer parts-fitment catalog"
    :class :operator-registered-fitment-catalog
    :access :operator-registered
    :url nil}])

(defn coverage
  "Honest, machine-checkable report of what this catalog actually
  covers -- never overstate ('全メーカー適合表' in prose, 1 free federal
  recall source + 1 structural operator-registered fitment-catalog
  class in fact)."
  []
  {:source-count (count catalog)
   :free-public-sources (into #{} (map :id (filter #(= :public-api (:access %)) catalog)))
   :note (str "R0 scope: NHTSA recalls (federal, free) + 1 structural "
              "operator-registered-fitment-catalog class. Citing one of "
              "these sources in a :flag-compatibility-concern proposal "
              "is informational only in this R0 -- it does NOT resolve "
              "or certify the concern (see README `Scope`).")})
