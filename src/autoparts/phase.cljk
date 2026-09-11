(ns autoparts.phase
  "Phase 0->3 staged rollout for the auto-parts-retail operations-
  coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- sales-record logging allowed,
                                      every write needs human approval.
    Phase 2  assisted-coordination -- adds restock-scheduling and
                                      compatibility-concern flagging,
                                      still approval.
    Phase 3  supervised-auto      -- governor-clean, high-confidence
                                      `:log-sales-record`/`:schedule-
                                      restocking-operation`/`:coordinate-
                                      supply-order` (below the cost
                                      threshold) may auto-commit.
                                      `:flag-compatibility-concern`
                                      NEVER auto-commits, at any phase.

  `:flag-compatibility-concern` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. This actor only
  SURFACES a part-compatibility/counterfeit/recall concern; it never
  resolves one on its own, and a human always looks at it, at any
  phase. `autoparts.governor`'s `:always-escalate?` gate on
  `:flag-compatibility-concern` enforces the same invariant
  independently -- two layers, not one, agree on this (the same
  discipline `cloud-itonami-isic-4730`'s `forecourt.phase` establishes
  for `:pump/dispense`/`:sale/settle`).

  `:coordinate-supply-order` IS eligible for phase-3 auto-commit --
  unlike `:flag-compatibility-concern`, it is not ALWAYS escalated by
  the governor, only CONDITIONALLY (above
  `autoparts.governor/supply-order-cost-threshold`); a small, clean,
  high-confidence, below-threshold supply-order-coordination proposal
  may auto-commit at phase 3, exactly as the task's own escalation
  rules specify.")

(def read-ops  #{})
(def write-ops #{:log-sales-record :schedule-restocking-operation
                 :flag-compatibility-concern :coordinate-supply-order})

;; NOTE the invariant: `:flag-compatibility-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"             :writes #{}
      :auto #{}}
   1 {:label "assisted-logging"      :writes #{:log-sales-record}
      :auto #{}}
   2 {:label "assisted-coordination" :writes #{:log-sales-record
                                               :schedule-restocking-operation
                                               :flag-compatibility-concern}
      :auto #{}}
   3 {:label "supervised-auto"       :writes write-ops
      :auto #{:log-sales-record :schedule-restocking-operation
              :coordinate-supply-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-compatibility-concern` is never auto-eligible at any phase,
    so it always escalates once the governor clears it (or holds if the
    governor doesn't -- but the governor itself always escalates it
    first; the phase gate below is the second, independent layer)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an AutoPartsOpsGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
