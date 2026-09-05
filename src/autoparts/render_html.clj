(ns autoparts.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack of THIS repo and renders whatever it
  produced. Nothing on the page is mock or hand-typed:

    - the graph is the real compiled `autoparts.operation/build`
      StateGraph, driven with `langgraph.graph/run*` exactly the way
      `autoparts.sim` (`clojure -M:dev:run`) drives it,
    - the SSoT is a fresh `autoparts.store/seed-db` MemStore, and every
      storefront / vendor / coordination-log row below is read BACK out
      of that store through the `Store` protocol after the run,
    - every HARD-hold rule name and every violation detail string is the
      `autoparts.governor`'s own `:violations` entry, off the ledger
      fact -- this namespace contains no rule text of its own,
    - the rollout-phase table is derived from `autoparts.phase/phases`,
      the governor-configuration table from `autoparts.governor` public
      vars, and the concern-source table from `autoparts.facts`.

  Subject provenance (the demo may not invent subjects): every subject
  driven below is one of the four ids `autoparts.store/demo-data`
  actually seeds -- storefronts `store-1` (independently verified) and
  `store-2` (unverified), vendors `vendor-1` (verified) and `vendor-2`
  (unverified). Verified against the seed before this file was written;
  `clojure -M:dev:run` was run first and its ledger inspected.

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [autoparts.facts :as facts]
            [autoparts.governor :as governor]
            [autoparts.operation :as op]
            [autoparts.partsopsadvisor :as advisor]
            [autoparts.phase :as phase]
            [autoparts.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  "The same operator context `autoparts.sim` uses."
  {:actor-id "op-1" :actor-role :parts-coordinator :phase phase/default-phase})

(def ^:private phase-1-coordinator
  "The SAME actor, run by an operator whose deployment is still at
  rollout phase 1 (`assisted-logging`). Used to show that the phase gate
  is a second, independent layer on top of the governor."
  (assoc coordinator :phase 1))

(defn- drifted-advisor
  "A deliberately DRIFTED advisor: same proposal shape, but it claims an
  `:effect` other than `:propose`. The shipped
  `autoparts.partsopsadvisor` structurally cannot emit this (every
  branch hard-codes `:propose`), so the only honest way to exercise the
  governor's `effect-propose-only` rule end-to-end is to swap the
  injected advisor -- which is exactly the seam
  `autoparts.operation/build` exposes for a real-LLM swap. The proposal
  still travels the whole graph; the governor rejects it."
  []
  (reify advisor/Advisor
    (-advise [_ st req]
      (assoc (advisor/infer st req) :effect :direct-write))))

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  graph while it is paused at `:request-approval`
  (`interrupt-before #{:request-approval}`). `:exercises` documents what
  the scenario is FOR; every other column on the timeline table is read
  off what the graph actually did."
  [{:tid "t01"
    :exercises "Sales-record intake against the independently verified storefront. Governor-clean, high confidence, and :log-sales-record is in phase 3's :auto set -> auto-commit with no human in the loop."
    :request {:op :log-sales-record :subject "store-1"
              :patch {:sku "sku-brake-pad-001" :qty -2 :type :sale :amount 4200.0}}}

   {:tid "t02"
    :exercises "Restocking-schedule draft for the same verified storefront. Also auto-eligible at phase 3 -> auto-commit. Drafts the record a warehouse system would act on; restocks nothing itself."
    :request {:op :schedule-restocking-operation :subject "store-1"
              :sku "sku-brake-pad-001" :qty 24 :requested-date "2026-07-20"}}

   {:tid "t03"
    :exercises "Supply-order coordination to the verified vendor, below the governor's cost threshold -> auto-commit. Transmits no purchase order."
    :request {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-brake-pad-001" :qty 200 :estimated-cost 3200.0}}

   {:tid "t04"
    :exercises "A part-compatibility / recall CONCERN. ALWAYS escalates at any confidence and any phase -- two independent layers (governor :always-escalate? and phase, which never lists this op as auto-eligible) agree. The human approves the FLAG, not a certification."
    :request {:op :flag-compatibility-concern :subject "store-1"
              :sku "sku-oil-filter-099" :concern-type :recall
              :detail "NHTSA recall campaign match reported by a customer"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t05"
    :exercises "Supply-order coordination ABOVE the governor's cost threshold. Governor otherwise clean and confidence high, but cost alone escalates it; the human buyer approves."
    :request {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-alternator-014" :qty 10 :estimated-cost 9000.0}
    :approval {:status :approved :by "op-1"}}

   {:tid "t06"
    :exercises "The same high-cost supply order, VETOED by the human buyer. Distinct from a HARD hold: the governor found nothing wrong, a person declined. Lands on the ledger as :approval-rejected."
    :request {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-alternator-014" :qty 40 :estimated-cost 36000.0}
    :approval {:status :rejected :by "op-1"}}

   {:tid "t07"
    :exercises "An op outside the closed allowlist that also names the banned finalization action. BOTH HARD rules fire; a human is never offered the decision, and the approval below is never reached."
    :request {:op :finalize-compatibility-certification :subject "store-1"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t08"
    :exercises "Sales-record intake initiated by the seeded UNVERIFIED pop-up storefront. HARD hold -- no proposal is ever committed on behalf of a party nobody independently verified."
    :request {:op :log-sales-record :subject "store-2"
              :patch {:sku "sku-brake-pad-001" :qty -1 :type :sale :amount 2100.0}}}

   {:tid "t09"
    :exercises "Supply-order coordination from the VERIFIED storefront to the seeded UNVERIFIED grey-market vendor. The initiating party is fine; the counterparty is not. HARD hold."
    :request {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-2"
              :sku "sku-brake-pad-001" :qty 50 :estimated-cost 900.0}}

   {:tid "t10"
    :exercises "An IN-SCOPE op (:log-sales-record, verified storefront, high confidence) carrying a structured flag that would finalize a part-compatibility certification. Caught on the :value flag alone -- never by scanning prose. HARD hold, permanently un-overridable."
    :request {:op :log-sales-record :subject "store-1"
              :patch {:sku "sku-oil-filter-099" :qty -1 :type :sale :amount 3300.0
                      :compatibility-certification-finalized? true}}
    :approval {:status :approved :by "op-1"}}

   {:tid "t11"
    :exercises "A DRIFTED advisor whose proposal claims :effect :direct-write instead of :propose. Everything else about the request is clean. HARD hold -- this actor commits proposals and nothing else."
    :advisor :drifted
    :request {:op :schedule-restocking-operation :subject "store-1"
              :sku "sku-wiper-blade-220" :qty 12 :requested-date "2026-07-28"}}

   {:tid "t12"
    :exercises "The SAME clean, governor-approved supply order as t03, run by an operator still at rollout phase 1 (assisted-logging). The governor cleared it; the phase gate holds it anyway (:phase-disabled). Second independent layer."
    :context :phase-1
    :request {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-brake-pad-001" :qty 200 :estimated-cost 3200.0}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actors {:keys [tid request approval advisor context] :as scenario}]
  (let [actor (get actors (or advisor :shipped))
        ctx   (if (= :phase-1 context) phase-1-coordinator coordinator)
        r1      (g/run* actor {:request request :context ctx} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2      (when (and approval paused?)
                  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final   (:state (or r2 r1))
        audit   (:audit final [])]
    (assoc scenario
           :phase       (:phase ctx)
           :verdict     (:verdict final)
           :paused?     paused?
           :escalation  (first (filter #(= :approval-requested (:t %)) audit))
           :hold-fact   (first (filter #(= :governor-hold (:t %)) audit))
           :human       (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a fresh MemStore, builds the real actor twice over the SAME
  store -- once with the shipped `mock-advisor`, once with the drifted
  advisor above -- and drives every scenario. Returns {:db .. :runs ..}."
  []
  (let [db     (store/seed-db)
        actors {:shipped (op/build db)
                :drifted (op/build db {:advisor (drifted-advisor)})}]
    {:db db :runs (mapv #(drive! actors %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"critical\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  `:basis` order is the governor's own evaluation order."
  [coll]
  (if (seq coll) (str/join " " (map code coll)) "—"))

(defn- kw-codes
  "Render a SET of keywords. Sorted -- a set has no order, and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every `:governor-hold` fact on the append-only ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- rule-holds
  "The subset of holds carrying at least one governor rule violation --
  i.e. a HARD compliance hold, as opposed to a rollout-phase hold."
  [db]
  (filterv #(seq (:violations %)) (holds db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n   (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over this actor's own append-only ledger after "
               "driving " (count runs) " requests through " (code "autoparts.operation/build")
               ". No usage, revenue or performance metric is claimed anywhere on this page.")
          (table ["Measure" "Count"]
                 [(tr "requests driven" (esc (count runs)))
                  (tr "ledger facts" (esc (count led)))
                  (tr (str "commits " (code ":committed")) (esc (n :committed)))
                  (tr (str "governor / phase holds " (code ":governor-hold")) (esc (n :governor-hold)))
                  (tr "of those, HARD governor-rule holds" (esc (count (rule-holds db))))
                  (tr (str "human vetoes " (code ":approval-rejected")) (esc (n :approval-rejected)))
                  (tr "human approvals granted"
                      (esc (count (filter #(= :approved (:human %)) runs))))]))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:always-escalate? verdict) " <span class=\"muted\">always-escalate</span>")
         (when (:high-cost? verdict) " <span class=\"muted\">over cost threshold</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit   "<span class=\"ok\">commit</span>"
    :hold     "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one " (code "langgraph.graph/run*") " over the compiled actor. The "
             "<em>Governor</em> column is the verdict map the governor itself returned; the "
             "<em>Human</em> column is the decision handed back to the graph while it was paused "
             "at " (code ":request-approval") ". " (code "t11") " is driven by a deliberately "
             "drifted advisor injected through " (code "operation/build") "'s "
             (code ":advisor") " seam; " (code "t12") " is driven by an operator context still at "
             "rollout phase 1.")
        (table ["Thread" "Op" "Subject" "Phase" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation hold-fact exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (esc (:phase r))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation " (code reason)
                                 "</span>"))
                          (when-let [reason (:phase-reason hold-fact)]
                            (str " <span class=\"muted\">phase gate " (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds (append-only ledger)"
          (str "Each row is one " (code ":violations") " entry of a " (code ":governor-hold")
               " fact. The rule name and the Japanese detail text are the governor's own output "
               "-- this page holds no rule text of its own. A HARD hold never reaches a human: it "
               "cannot be approved away.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- phase-holds-section [db]
  (let [ps (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) (ledger-of db))]
    (when (seq ps)
      (card "Rollout-phase holds"
            (str "A hold with an EMPTY " (code ":basis") ": the governor found no violation, and "
                 (code "autoparts.phase/gate") " -- the second, independent layer -- refused the "
                 "write anyway because the op is not yet enabled at that deployment's rollout "
                 "phase.")
            (table ["Op" "Subject" "Phase" "Phase reason" "Confidence"]
                   (for [p ps]
                     (tr (code (:op p)) (code (:subject p)) (fmt (:phase p))
                         (code (:phase-reason p)) (fmt (:confidence p)))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human vetoes"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, with basis " (code ":approver-rejected") " -- not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

;; The ONE hand-written table on this page: a static description of the
;; fixed op-gate contract -- which of the four scoped ops can ever
;; auto-commit, and which is permanently human-only. Documentation of
;; fixed behavior, not runtime telemetry. The two derived columns beside
;; it ARE read from `autoparts.phase/phases`, so a drift between this
;; prose and the code shows up as a contradiction on the page itself.
(def ^:private op-gate-contract
  {:log-sales-record
   "may auto-commit when the governor is clean at phase 3"
   :schedule-restocking-operation
   "may auto-commit when the governor is clean at phase 3; drafts a schedule, restocks nothing"
   :flag-compatibility-concern
   "ALWAYS a human decision, at every phase and every confidence -- surfaces a concern, never resolves or certifies one"
   :coordinate-supply-order
   "may auto-commit when the governor is clean at phase 3 AND below the cost threshold; drafts an order, transmits nothing"})

(defn- phase-section []
  (let [ph (:phase coordinator)
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Op gate — rollout phase " ph " (" label ")")
          (str "The <em>may write</em> and <em>may auto-commit</em> columns are derived from "
               (code "autoparts.phase/phases") "; the <em>fixed contract</em> column is a static "
               "description of the closed op contract (README <code>Scope</code>). A governor HOLD "
               "always stays a HOLD; an op that may write but is not auto-eligible escalates to a "
               "human even when the governor is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"
                  "Fixed contract"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")
                       (esc (get op-gate-contract o "—"))))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "autoparts.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor (SOFT — escalates)" (code governor/confidence-floor))
                (tr "supply-order cost threshold (SOFT — escalates)"
                    (code governor/supply-order-cost-threshold))
                (tr "closed op allowlist (HARD)" (kw-codes governor/allowed-ops))
                (tr "banned finalization ops (HARD, permanent)"
                    (kw-codes governor/banned-finalization-ops))])))

(defn- parties-section [db]
  (let [led (ledger-of db)
        seed (store/demo-data)
        last-fact (fn [id] (last (filter #(= id (:subject %)) led)))
        status (fn [id]
                 (let [f (last-fact id)]
                   (cond
                     (nil? f) "<span class=\"muted\">no ledger activity</span>"
                     (= :committed (:t f)) "<span class=\"ok\">last op committed</span>"
                     (= :approval-rejected (:t f))
                     "<span class=\"critical\">last op vetoed by approver</span>"
                     (= :governor-hold (:t f))
                     (str "<span class=\"critical\">last op held</span> " (codes (:basis f)))
                     :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))]
    (card "Parties (verified-party gate)"
          (str "Read back through the " (code "Store") " protocol after the run -- "
               (code "store/storefront") " and " (code "store/vendor") ", for the ids "
               (code "store/demo-data") " actually seeds. An unverified party can never have a "
               "proposal committed on its behalf, and a vendor is checked independently of the "
               "storefront initiating the order.")
          (table ["Kind" "Id" "Name" "verified?" "Ledger status"]
                 (concat
                  (for [id (sort (keys (:storefronts seed)))
                        :let [s (store/storefront db id)]]
                    (tr "storefront" (code (:id s)) (esc (:name s)) (flag (:verified? s))
                        (status id)))
                  (for [id (sort (keys (:vendors seed)))
                        :let [v (store/vendor db id)]]
                    (tr "vendor" (code (:id v)) (esc (:name v)) (flag (:verified? v))
                        (status id))))))))

(defn- log-card [title note headers row-fn rows]
  (card title note
        (if (seq rows)
          (table headers (map row-fn rows))
          "<p class=\"muted\">none committed in this run</p>")))

(defn- coordination-logs-section [db]
  (str
   (log-card "Sales-record coordination log"
             (str "Committed entries from " (code "store/sales-log") ". The "
                  (code ":coordination-id") " is minted at commit time by "
                  (code "autoparts.registry/register-coordination-record") ".")
             ["Coordination id" "Storefront" "SKU" "Qty" "Type" "Amount"]
             (fn [r] (tr (code (:coordination-id r)) (code (:storefront-id r))
                         (fmt (:sku r)) (fmt (:qty r)) (fmt (:type r)) (fmt (:amount r))))
             (store/sales-log db))
   "\n"
   (log-card "Restocking-schedule coordination log"
             (str "Committed entries from " (code "store/restock-log")
                  ". A draft a warehouse system acts on -- this actor restocks no shelf.")
             ["Coordination id" "Storefront" "SKU" "Qty" "Requested date"]
             (fn [r] (tr (code (:coordination-id r)) (code (:storefront-id r))
                         (fmt (:sku r)) (fmt (:qty r)) (fmt (:requested-date r))))
             (store/restock-log db))
   "\n"
   (log-card "Compatibility-concern log"
             (str "Committed entries from " (code "store/concern-log")
                  ". Every one of these reached a human first -- this op is never auto-committed "
                  "at any phase. Flagging a concern is not resolving it.")
             ["Coordination id" "Storefront" "SKU" "Concern type" "Detail"]
             (fn [r] (tr (code (:coordination-id r)) (code (:storefront-id r))
                         (fmt (:sku r)) (fmt (:concern-type r)) (fmt (:detail r))))
             (store/concern-log db))
   "\n"
   (log-card "Supply-order coordination log"
             (str "Committed entries from " (code "store/supply-order-log")
                  ". A draft a human buyer acts on -- no purchase order is transmitted and no "
                  "payment is settled.")
             ["Coordination id" "Storefront" "Vendor" "SKU" "Qty" "Estimated cost"]
             (fn [r] (tr (code (:coordination-id r)) (code (:storefront-id r))
                         (code (:vendor-id r)) (fmt (:sku r)) (fmt (:qty r))
                         (fmt (:estimated-cost r))))
             (store/supply-order-log db))))

(defn- facts-section []
  (let [{:keys [source-count note]} (facts/coverage)]
    (card "Concern-source catalog"
          (str "Honest coverage report from " (code "autoparts.facts/coverage") ": "
               (esc source-count) " source(s). " (esc note))
          (table ["Id" "Name" "Class" "Access" "URL"]
                 (for [f facts/catalog]
                   (tr (code (:id f)) (esc (:name f)) (code (:class f)) (code (:access f))
                       (if-let [u (:url f)]
                         (str "<a href=\"" (esc u) "\">" (esc u) "</a>")
                         "—")))))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "autoparts.store/ledger")
             " returns it. Note that " (code ":approval-granted") " is emitted to the graph's "
             "in-memory " (code ":audit") " channel only -- " (code "autoparts.operation")
             " never appends it to the store ledger, so an approved request is visible here as "
             "the " (code ":committed") " fact it produced.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-4530 (autoparts)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<span class=\"badge\">ISIC 4530</span>"
       "<span class=\"badge\">autoparts</span>"
       "<span class=\"badge\">auto-parts-ops-governor</span>"
       "</header>\n"
       "<h1>Sale of motor vehicle parts and accessories — operator console</h1>"
       "<p class=\"subtitle\">actor <code>" (esc (:actor-id coordinator))
       "</code> · role <code>" (esc (:actor-role coordinator))
       "</code> · default rollout phase <code>" (esc phase/default-phase)
       "</code> · read-only sample, governor-gated, compatibility concerns always human-decided</p>\n"
       "<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (phase-holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (parties-section db)
                          (coordination-logs-section db)
                          (facts-section)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>autoparts.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>autoparts.operation</code> actor graph over the real "
       "<code>autoparts.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "This actor coordinates only: it never restocks a shelf, transmits a purchase order, "
       "settles a sale, or certifies part compatibility."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)
        hard (rule-holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is
    ;; not evidence of a governor. Do not weaken this.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (when (empty? hard)
      (throw (ex-info "no :governor-hold fact carries a governor rule violation — refusing to write a console whose only holds are rollout-phase holds"
                      {:holds (count hs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hard) " HARD governor-rule holds, "
                  (count hs) " total holds, "
                  (count runs) " requests)"))))
