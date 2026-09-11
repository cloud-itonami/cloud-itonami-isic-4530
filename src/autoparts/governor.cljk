(ns autoparts.governor
  "AutoPartsOpsGovernor -- the independent compliance layer that earns
  the PartsOpsAdvisor the right to commit a coordination proposal. The
  LLM has no notion of which storefront/vendor has actually been
  independently verified, whether its own proposed `:effect` value is
  the only one this actor is EVER allowed to commit, or whether it has
  drifted from the closed set of ops this actor is scoped to -- so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Scope reminder (see README `Scope`): this actor is an auto-parts-
  retail OPERATIONS COORDINATION actor. It is NOT a part-compatibility
  certification authority and NOT a recall-remediation authority --
  those judgments belong to the manufacturer, a certified inspection
  body, or the relevant regulator. `:itonami.blueprint/governor` is
  `:auto-parts-ops-governor`, grep-verified UNIQUE fleet-wide.

  Four checks, in priority order. ALL are HARD violations: a human
  approver CANNOT override them. The confidence floor plus two
  always-escalate rules are SOFT: they route to a human, who may
  approve.

    1. closed-op-allowlist               -- is `:op` one of the four
                                             ops this actor is scoped
                                             to? Anything else is
                                             rejected, never inferred
                                             against.
    2. verified-party-gate               -- has the initiating
                                             storefront (and, for
                                             `:coordinate-supply-order`,
                                             the target vendor) been
                                             INDEPENDENTLY verified/
                                             registered? An unverified
                                             party can never have a
                                             proposal committed on its
                                             behalf.
    3. effect-propose-only               -- does the proposal's
                                             `:effect` equal `:propose`,
                                             and ONLY `:propose`? This
                                             actor never directly
                                             finalizes/executes
                                             anything.
    4. compatibility-certification-
       finalization-block               -- a PERMANENT, un-overridable
                                             block on any proposal that
                                             would directly finalize a
                                             part-compatibility
                                             certification. Checked via
                                             STRUCTURED fields only
                                             (an exact `:op` match on
                                             the banned finalize-action
                                             keyword, or an exact
                                             boolean flag inside
                                             `:value`) -- deliberately
                                             NEVER by scanning
                                             `:rationale`/`:summary`
                                             prose for a bare noun like
                                             \"compatibility\" or
                                             \"certification\", which
                                             the PartsOpsAdvisor's own
                                             DEFAULT `:flag-
                                             compatibility-concern`
                                             rationale legitimately
                                             contains (a documented
                                             self-trip bug class this
                                             fleet has independently
                                             hit and fixed more than
                                             once -- see
                                             `test/autoparts/
                                             governor_contract_test.
                                             cljc`'s
                                             `default-mock-advisor-
                                             proposals-never-self-trip-
                                             scope-exclusion`).

    5. confidence floor                  -- SOFT. LLM confidence below
                                             threshold -> escalate.
    6. compatibility-concern-always-
       escalates                        -- SOFT, but UNCONDITIONAL:
                                             `:flag-compatibility-
                                             concern` ALWAYS escalates
                                             to a human, at any
                                             confidence, any phase --
                                             this actor only SURFACES a
                                             concern, it never resolves
                                             one on its own.
    7. supply-order-cost-threshold       -- SOFT: a `:coordinate-
                                             supply-order` whose
                                             `:estimated-cost` exceeds
                                             `supply-order-cost-
                                             threshold` escalates,
                                             regardless of confidence."
  (:require [autoparts.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def supply-order-cost-threshold
  "Above this amount (the storefront's operating currency, e.g. JPY),
  a :coordinate-supply-order proposal ALWAYS escalates to a human, even
  when the governor is otherwise clean and confidence is high."
  5000.0)

(def allowed-ops
  "The closed set of proposal ops this actor is EVER scoped to. Any
  other `:op` is rejected outright -- the PartsOpsAdvisor is never
  asked to infer, and the governor never lets it through, for an op
  outside this set."
  #{:log-sales-record :schedule-restocking-operation
    :flag-compatibility-concern :coordinate-supply-order})

;; A permanent, un-overridable block: these are the ONLY structured
;; signals `compatibility-certification-finalization-violations` checks
;; for. Phrased as the finalization/execution ACTION (a banned op
;; keyword, and boolean :value flags that themselves say "finalized"/
;; "finalize"), never as a bare noun -- see the governor's docstring
;; check 4 and the dedicated regression test.
(def banned-finalization-ops
  #{:finalize-compatibility-certification :certify-part-compatibility})

;; ───────────────────────── checks ─────────────────────────

(defn- closed-op-allowlist-violations
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :closed-op-allowlist
      :detail (str op " はこのアクターのスコープ外の操作 -- 許可された4操作のいずれでもない")}]))

(defn- verified-party-violations
  "The initiating storefront must be an independently verified/
  registered record. For `:coordinate-supply-order`, the target vendor
  (`:value :vendor-id`) must ALSO be independently verified."
  [{:keys [op subject]} proposal st]
  (let [sf (store/storefront st subject)
        sf-violation (when-not (and sf (:verified? sf))
                       [{:rule :storefront-not-verified
                         :detail (str subject " は独立検証済みのstorefrontレコードではない")}])
        vendor-id (get-in proposal [:value :vendor-id])
        vd (when (= op :coordinate-supply-order) (store/vendor st vendor-id))
        vd-violation (when (and (= op :coordinate-supply-order)
                                 (not (and vd (:verified? vd))))
                       [{:rule :vendor-not-verified
                         :detail (str vendor-id " は独立検証済みのvendorレコードではない")}])]
    (into [] (concat sf-violation vd-violation))))

(defn- effect-propose-only-violations
  "This actor NEVER commits anything other than `:propose` -- see
  README `Scope`. A proposal claiming any other effect is rejected,
  regardless of which op it claims to serve."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の :effect が :propose ではない: " (pr-str (:effect proposal)))}]))

(defn- compatibility-certification-finalization-violations
  "A PERMANENT block, checked via STRUCTURED fields only (never by
  scanning free-text rationale/summary for a bare noun -- see the ns
  docstring check 4)."
  [{:keys [op]} proposal]
  (let [value (:value proposal)]
    (when (or (contains? banned-finalization-ops op)
              (true? (:compatibility-certification-finalized? value))
              (true? (:certification-finalize? value)))
      [{:rule :compatibility-certification-finalization-blocked
        :detail "part-compatibility認証の確定を直接行う提案は恒久的に禁止 -- 本アクターの権限外(README Scope参照)"}])))

(defn check
  "Censors a PartsOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :hard? bool :always-escalate? bool :high-cost? bool}."
  [request proposal st]
  (let [hard (into []
                   (concat (closed-op-allowlist-violations request)
                           (verified-party-violations request proposal st)
                           (effect-propose-only-violations proposal)
                           (compatibility-certification-finalization-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        always-escalate? (= :flag-compatibility-concern (:op request))
        estimated-cost (get-in proposal [:value :estimated-cost])
        high-cost? (and (= :coordinate-supply-order (:op request))
                        (number? estimated-cost)
                        (> estimated-cost supply-order-cost-threshold))
        hard? (boolean (seq hard))]
    {:ok?              (and (not hard?) (not low?) (not always-escalate?) (not high-cost?))
     :violations       hard
     :confidence       conf
     :hard?            hard?
     :escalate?        (and (not hard?) (or low? always-escalate? high-cost?))
     :always-escalate? always-escalate?
     :high-cost?       high-cost?}))

(defn hold-fact
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
