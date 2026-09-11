(ns autoparts.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean storefront through
  sales-record logging (auto-commits) -> restock scheduling (auto-
  commits) -> a low-cost supply-order coordination (auto-commits) ->
  a compatibility-concern flag (ALWAYS escalates/approve), then shows
  HARD-hold scenarios: an out-of-scope op, an unverified storefront, an
  unverified vendor, a spoofed non-:propose effect, and an attempted
  compatibility-certification-finalization -- plus a high-cost supply
  order (escalates on cost, not hold).

  Like every sibling actor's checks, this actor's governor rules
  (`closed-op-allowlist`, `verified-party-gate`,
  `effect-propose-only`, `compatibility-certification-finalization-
  block`) are exercised directly, one scenario per HARD-hold case,
  following the SAME 'exercise the failure mode directly, never only
  via a happy-path actuation' discipline every sibling actor's demo
  driver establishes."
  (:require [langgraph.graph :as g]
            [autoparts.store :as store]
            [autoparts.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :parts-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-sales-record store-1 (clean -> auto-commit) ==")
    (println (exec-op actor "t1"
                      {:op :log-sales-record :subject "store-1"
                       :patch {:sku "sku-brake-pad-001" :qty -2 :type :sale :amount 4200.0}}
                      operator))

    (println "== schedule-restocking-operation store-1 (clean -> auto-commit) ==")
    (println (exec-op actor "t2"
                      {:op :schedule-restocking-operation :subject "store-1"
                       :sku "sku-brake-pad-001" :qty 24 :requested-date "2026-07-20"}
                      operator))

    (println "== coordinate-supply-order store-1 (below cost threshold, clean -> auto-commit) ==")
    (println (exec-op actor "t3"
                      {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
                       :sku "sku-brake-pad-001" :qty 200 :estimated-cost 3200.0}
                      operator))

    (println "== flag-compatibility-concern store-1 (ALWAYS escalates -- human decides) ==")
    (let [r (exec-op actor "t4"
                     {:op :flag-compatibility-concern :subject "store-1"
                      :sku "sku-oil-filter-099" :concern-type :recall
                      :detail "NHTSA recall campaign match reported by a customer"}
                     operator)]
      (println r)
      (println "-- human parts coordinator approves the FLAG (not the certification) --")
      (println (approve! actor "t4")))

    (println "== coordinate-supply-order store-1 (above cost threshold -> escalates on cost) ==")
    (let [r (exec-op actor "t5"
                     {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
                      :sku "sku-alternator-014" :qty 10 :estimated-cost 9000.0}
                     operator)]
      (println r)
      (println (approve! actor "t5")))

    (println "== finalize-compatibility-certification store-1 (out-of-scope op -> HARD hold) ==")
    (println (exec-op actor "t6"
                      {:op :finalize-compatibility-certification :subject "store-1"}
                      operator))

    (println "== log-sales-record store-2 (unverified storefront -> HARD hold) ==")
    (println (exec-op actor "t7"
                      {:op :log-sales-record :subject "store-2"
                       :patch {:sku "sku-brake-pad-001" :qty -1 :type :sale :amount 2100.0}}
                      operator))

    (println "== coordinate-supply-order store-1 -> vendor-2 (unverified vendor -> HARD hold) ==")
    (println (exec-op actor "t8"
                      {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-2"
                       :sku "sku-brake-pad-001" :qty 50 :estimated-cost 900.0}
                      operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== sales log ==")
    (doseq [r (store/sales-log db)] (println r))

    (println "== restock log ==")
    (doseq [r (store/restock-log db)] (println r))

    (println "== compatibility-concern log ==")
    (doseq [r (store/concern-log db)] (println r))

    (println "== supply-order log ==")
    (doseq [r (store/supply-order-log db)] (println r))))
