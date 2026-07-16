(ns autoparts.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-compatibility-concern` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [autoparts.phase :as phase]))

(deftest compatibility-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a compatibility-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-compatibility-concern))
          (str "phase " n " must not auto-commit :flag-compatibility-concern")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-the-no-capital-risk-and-below-threshold-ops
  (testing ":flag-compatibility-concern is the ONLY write op excluded from phase-3 auto"
    (is (= #{:log-sales-record :schedule-restocking-operation :coordinate-supply-order}
           (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-sales-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-compatibility-concern} :commit)))))

(deftest gate-auto-eligible-write-passes-through-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :coordinate-supply-order} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-sales-record} :commit))))
  (is (= :hold (:disposition (phase/gate 1 {:op :coordinate-supply-order} :commit)))
      "phase 1 only enables :log-sales-record"))

(deftest phase-2-adds-restock-and-concern-but-still-needs-approval
  (is (= #{:log-sales-record :schedule-restocking-operation :flag-compatibility-concern}
         (:writes (get phase/phases 2))))
  (is (= #{} (:auto (get phase/phases 2))) "phase 2 never auto-commits anything"))
