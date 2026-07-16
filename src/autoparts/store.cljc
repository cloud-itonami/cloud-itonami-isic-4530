(ns autoparts.store
  "SSoT for the auto-parts-retail operations-coordination actor, behind
  a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every prior `cloud-itonami-isic-*` actor in this fleet
  uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/autoparts/store_contract_test.cljc), which is the whole point:
  the actor, the AutoPartsOpsGovernor and the audit ledger never know
  which SSoT they run on.

  This store adopts BOTH `kotoba-lang/langchain-store` (ADR-2607141600)
  patterns in one place: `storefront`/`vendor` are ENTITY stores
  (field-spec driven, `ls/map->tx` / `ls/pull->map` / `ls/pull-pattern`
  -- the `underwriting.store` / `cloud-itonami-isic-6511` discipline),
  and the audit ledger + the four coordination logs (sales / restock /
  concern / supply-order) are seq-keyed EDN-blob EVENT STREAMS
  (`ls/read-stream` / `ls/append-blob!`). No hand-rolled `enc`/`dec*`
  codec here -- the exact two-liner ~190 sibling stores duplicate.

  Every proposal this actor ever commits carries `:effect :propose`
  (see `autoparts.governor`'s `effect-propose-only-violations`) -- this
  actor coordinates (drafts a record of what SHOULD happen next), it
  never itself restocks a shelf, transmits a purchase order, or settles
  a sale. `commit-record!` therefore dispatches on the record's `:op`
  (which of the four coordination kinds), not on `:effect` (which is
  always the same value and would not distinguish anything).

  The ledger stays append-only on every backend: 'which storefront/
  vendor was verified before a coordination proposal was accepted,
  which sales record was logged, which restock was scheduled, which
  compatibility concern was flagged, which supply order was
  coordinated, approved by whom' is always a query over an immutable
  log -- the audit trail an operator, a franchisor, or a regulator
  trusting this actor needs."
  (:require [autoparts.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (storefront [s id])
  (vendor [s id])
  (ledger [s])
  (sales-log [s] "the append-only :log-sales-record coordination history")
  (restock-log [s] "the append-only :schedule-restocking-operation coordination history")
  (concern-log [s] "the append-only :flag-compatibility-concern coordination history")
  (supply-order-log [s] "the append-only :coordinate-supply-order coordination history")
  (next-coordination-sequence [s storefront-id op-kind] "next per-storefront-per-kind sequence")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-storefronts [s storefronts] "replace/seed the storefront directory (map id->storefront)")
  (with-vendors [s vendors] "replace/seed the vendor directory (map id->vendor)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained storefront/vendor set covering the
  verified-party-gate HARD check (one verified, one unverified
  storefront; one verified, one unverified vendor) so the actor + tests
  run offline. Each unverified party isolates exactly ONE failure mode
  (the rest stay clean), following the 'exercise the failure mode
  directly, never only via a happy-path actuation' discipline every
  sibling governor's demo data establishes."
  []
  {:storefronts
   {"store-1" {:id "store-1" :name "パーツ館 渋谷店 (独立検証済み)" :verified? true}
    "store-2" {:id "store-2" :name "未登録ポップアップ出店" :verified? false}}
   :vendors
   {"vendor-1" {:id "vendor-1" :name "Bosch純正部品卸 (独立検証済み)" :verified? true}
    "vendor-2" {:id "vendor-2" :name "未確認並行輸入業者" :verified? false}}})

;; ----------------------------- shared commit logic -----------------------------

(def op->code
  "op keyword -> short uppercase log-record tag, shared by both backends."
  {:log-sales-record               "SALES"
   :schedule-restocking-operation  "RESTOCK"
   :flag-compatibility-concern     "CONCERN"
   :coordinate-supply-order        "SUPPLY"})

(defn- draft-coordination-record [s op storefront-id]
  (let [seq-n (next-coordination-sequence s storefront-id op)]
    (registry/register-coordination-record (get op->code op) storefront-id seq-n)))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (storefront [_ id] (get-in @a [:storefronts id]))
  (vendor [_ id] (get-in @a [:vendors id]))
  (ledger [_] (:ledger @a))
  (sales-log [_] (:sales-log @a))
  (restock-log [_] (:restock-log @a))
  (concern-log [_] (:concern-log @a))
  (supply-order-log [_] (:supply-order-log @a))
  (next-coordination-sequence [_ storefront-id op-kind]
    (get-in @a [:sequences storefront-id op-kind] 0))
  (commit-record! [s {:keys [op path value]}]
    (let [storefront-id (first path)
          log-key (case op
                    :log-sales-record              :sales-log
                    :schedule-restocking-operation  :restock-log
                    :flag-compatibility-concern      :concern-log
                    :coordinate-supply-order         :supply-order-log
                    nil)]
      (when log-key
        (let [rec (draft-coordination-record s op storefront-id)
              entry (assoc value
                           :storefront-id storefront-id
                           :coordination-id (get rec "coordination_id"))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:sequences storefront-id op] (fnil inc 0))
                         (update log-key (fnil conj []) entry))))
          rec)))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-storefronts [s storefronts] (when (seq storefronts) (swap! a assoc :storefronts storefronts)) s)
  (with-vendors [s vendors] (when (seq vendors) (swap! a assoc :vendors vendors)) s))

(defn seed-db
  "A MemStore seeded with the demo storefront/vendor set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sales-log [] :restock-log []
                           :concern-log [] :supply-order-log [] :sequences {}))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

;; Schema, the EDN-blob codec and the entity map<->tx<->pull triples are
;; the shared kotoba-lang/langchain-store machinery (ADR-2607141600).
;; storefront/vendor are the entity-store half; the ledger and the four
;; coordination logs are the event-stream half (`ls/read-stream` /
;; `ls/append-blob!`).
(def ^:private schema
  (ls/identity-schema [:storefront/id :vendor/id
                       :ledger/seq :sales/seq :restock/seq :concern/seq :supply/seq
                       :sequence/key]))

(def ^:private storefront-spec
  {:id {:attr :storefront/id}
   :name {:attr :storefront/name}
   :verified? {:attr :storefront/verified? :coerce boolean}})

(def ^:private vendor-spec
  {:id {:attr :vendor/id}
   :name {:attr :vendor/name}
   :verified? {:attr :vendor/verified? :coerce boolean}})

(defn- storefront->tx [m] (ls/map->tx storefront-spec m))
(def ^:private storefront-pull (ls/pull-pattern storefront-spec))
(defn- pull->storefront [m] (ls/pull->map storefront-spec :id m))

(defn- vendor->tx [m] (ls/map->tx vendor-spec m))
(def ^:private vendor-pull (ls/pull-pattern vendor-spec))
(defn- pull->vendor [m] (ls/pull->map vendor-spec :id m))

(def ^:private log-streams
  "op-kind -> [seq-attr edn-attr]."
  {:log-sales-record              [:sales/seq :sales/record]
   :schedule-restocking-operation [:restock/seq :restock/record]
   :flag-compatibility-concern    [:concern/seq :concern/record]
   :coordinate-supply-order       [:supply/seq :supply/record]})

(defn- seq-key [storefront-id op-kind]
  (str storefront-id ":" (name op-kind)))

(defrecord DatomicStore [conn]
  Store
  (storefront [_ id]
    (pull->storefront (d/pull (d/db conn) storefront-pull [:storefront/id id])))
  (vendor [_ id]
    (pull->vendor (d/pull (d/db conn) vendor-pull [:vendor/id id])))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (sales-log [_] (let [[sa ea] (get log-streams :log-sales-record)] (ls/read-stream conn sa ea)))
  (restock-log [_] (let [[sa ea] (get log-streams :schedule-restocking-operation)] (ls/read-stream conn sa ea)))
  (concern-log [_] (let [[sa ea] (get log-streams :flag-compatibility-concern)] (ls/read-stream conn sa ea)))
  (supply-order-log [_] (let [[sa ea] (get log-streams :coordinate-supply-order)] (ls/read-stream conn sa ea)))
  (next-coordination-sequence [_ storefront-id op-kind]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (seq-key storefront-id op-kind))
        0))
  (commit-record! [s {:keys [op path value]}]
    (let [storefront-id (first path)]
      (when-let [[seq-attr edn-attr] (get log-streams op)]
        (let [rec (draft-coordination-record s op storefront-id)
              entry (assoc value
                           :storefront-id storefront-id
                           :coordination-id (get rec "coordination_id"))
              next-n (inc (next-coordination-sequence s storefront-id op))
              stream-seq (count (case op
                                  :log-sales-record (sales-log s)
                                  :schedule-restocking-operation (restock-log s)
                                  :flag-compatibility-concern (concern-log s)
                                  :coordinate-supply-order (supply-order-log s)))]
          (d/transact! conn
                       [{:sequence/key (seq-key storefront-id op) :sequence/next next-n}])
          (ls/append-blob! conn seq-attr edn-attr stream-seq entry)
          rec)))
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-storefronts [s storefronts]
    (when (seq storefronts) (d/transact! conn (mapv storefront->tx (vals storefronts)))) s)
  (with-vendors [s vendors]
    (when (seq vendors) (d/transact! conn (mapv vendor->tx (vals vendors)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:storefronts .. :vendors ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [storefronts vendors]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-storefronts storefronts) (with-vendors vendors)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo storefront/vendor set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
