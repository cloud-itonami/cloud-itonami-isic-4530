(ns autoparts.registry
  "Pure-function COORDINATION-record construction -- builds the
  auditable log entry for each committed proposal (sales-record log /
  restock-schedule log / compatibility-concern log / supply-order-
  coordination log). Pure data + pure functions -- no I/O, no network
  call to any real POS / warehouse-management / procurement system. It
  builds the RECORD an operator would keep, not the act of actually
  restocking a shelf, actually transmitting a purchase order to a
  vendor, or actually settling a sale (those remain outside this
  actor's scope -- see README `Scope`).

  Unlike a sibling actor that drafts an UNSIGNED CERTIFICATE for its
  own governed act (e.g. `cloud-itonami-isic-4730`'s
  `forecourt.registry/register-dispense-record`), this namespace
  deliberately has NO certificate-issuing function of any kind. This
  actor never certifies part-compatibility, never issues a recall-
  remediation attestation, and never issues any other credential --
  `autoparts.governor`'s `compatibility-certification-finalization-
  violations` check is the RUNTIME enforcement of that boundary; the
  absence of a certificate-builder here is the STRUCTURAL one (there is
  nothing to call even if the governor were bypassed)."
  (:require [kotoba.lang.text :as str]))

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-coordination-record
  "Validate + construct a coordination-log entry DRAFT. `op-code` is a
  short uppercase tag for the kind of coordination event (\"SALES\" /
  \"RESTOCK\" / \"CONCERN\" / \"SUPPLY\"); `storefront-id` scopes the
  sequence; `sequence` is the next per-storefront-per-kind counter.
  Pure function -- does not touch any real POS/warehouse/procurement
  system."
  [op-code storefront-id sequence]
  (when-not (and op-code (not= op-code ""))
    (throw (ex-info "coordination-record: op-code required" {})))
  (when-not (and storefront-id (not= storefront-id ""))
    (throw (ex-info "coordination-record: storefront-id required" {})))
  (when (< sequence 0)
    (throw (ex-info "coordination-record: sequence must be >= 0" {})))
  (let [coordination-id (str (str/upper storefront-id) "-" op-code "-" (zero-pad sequence 6))]
    {"record" {"record_id" coordination-id
               "kind" (str (str/lower op-code) "-coordination-draft")
               "storefront_id" storefront-id
               "immutable" true}
     "coordination_id" coordination-id}))
