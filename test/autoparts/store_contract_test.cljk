(ns autoparts.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on a sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (true? (:verified? (store/storefront s "store-1"))))
      (is (false? (:verified? (store/storefront s "store-2"))))
      (is (true? (:verified? (store/vendor s "vendor-1"))))
      (is (false? (:verified? (store/vendor s "vendor-2"))))
      (is (nil? (store/storefront s "nope")))
      (is (nil? (store/vendor s "nope")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/sales-log s)))
      (is (= [] (store/restock-log s)))
      (is (= [] (store/concern-log s)))
      (is (= [] (store/supply-order-log s)))
      (is (zero? (store/next-coordination-sequence s "store-1" :log-sales-record)))
      (is (zero? (store/next-coordination-sequence s "store-1" :coordinate-supply-order))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "log-sales-record commits an entry into the sales log"
        (store/commit-record! s {:effect :propose :op :log-sales-record
                                 :path ["store-1"]
                                 :value {:sku "sku-1" :qty -2 :type :sale :amount 4200.0}})
        (is (= 1 (count (store/sales-log s))))
        (is (= "sku-1" (:sku (first (store/sales-log s)))))
        (is (= "store-1" (:storefront-id (first (store/sales-log s)))))
        (is (string? (:coordination-id (first (store/sales-log s))))))
      (testing "schedule-restocking-operation commits an entry and advances its own sequence"
        (store/commit-record! s {:effect :propose :op :schedule-restocking-operation
                                 :path ["store-1"]
                                 :value {:sku "sku-1" :qty 24 :requested-date "2026-07-20"}})
        (is (= 1 (count (store/restock-log s))))
        (is (= 24 (:qty (first (store/restock-log s)))))
        (is (= 1 (store/next-coordination-sequence s "store-1" :schedule-restocking-operation))))
      (testing "flag-compatibility-concern commits an entry into the concern log"
        (store/commit-record! s {:effect :propose :op :flag-compatibility-concern
                                 :path ["store-1"]
                                 :value {:sku "sku-2" :concern-type :recall :detail "d"}})
        (is (= 1 (count (store/concern-log s))))
        (is (= :recall (:concern-type (first (store/concern-log s))))))
      (testing "coordinate-supply-order commits an entry into the supply-order log"
        (store/commit-record! s {:effect :propose :op :coordinate-supply-order
                                 :path ["store-1"]
                                 :value {:vendor-id "vendor-1" :sku "sku-1" :qty 200 :estimated-cost 3200.0}})
        (is (= 1 (count (store/supply-order-log s))))
        (is (= "vendor-1" (:vendor-id (first (store/supply-order-log s))))))
      (testing "each coordination kind has its OWN independent per-storefront sequence"
        (is (= 1 (store/next-coordination-sequence s "store-1" :log-sales-record)))
        (is (= 1 (store/next-coordination-sequence s "store-1" :flag-compatibility-concern)))
        (is (= 1 (store/next-coordination-sequence s "store-1" :coordinate-supply-order)))
        (is (zero? (store/next-coordination-sequence s "store-2" :log-sales-record))
            "sequences are scoped per-storefront, store-2 untouched"))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/storefront s "nope")))
    (is (nil? (store/vendor s "nope")))
    (is (= [] (store/ledger s)))
    (is (= [] (store/sales-log s)))
    (is (zero? (store/next-coordination-sequence s "store-9" :log-sales-record)))
    (store/with-storefronts s {"x" {:id "x" :name "Test Store" :verified? true}})
    (store/with-vendors s {"y" {:id "y" :name "Test Vendor" :verified? false}})
    (is (true? (:verified? (store/storefront s "x"))))
    (is (false? (:verified? (store/vendor s "y"))))))
