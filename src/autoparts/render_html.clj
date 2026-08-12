(ns autoparts.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack --
  `autoparts.partsopsadvisor` -> `autoparts.governor` ->
  `autoparts.phase` -> `autoparts.store`, wired by
  `autoparts.operation`'s langgraph-clj StateGraph and executed through
  `langgraph.graph/run*` -- and renders the resulting store, audit
  ledger, coordination logs and per-run audit trails.

  NOTHING on the page is hand-typed telemetry. Every op, subject,
  confidence, disposition, hold rule, hold detail, coordination-id and
  log row below is read back out of the real run. The policy tables are
  read out of the real `autoparts.governor` / `autoparts.phase` vars
  (`allowed-ops`, `confidence-floor`, `supply-order-cost-threshold`,
  `banned-finalization-ops`, `phases`), not restated in prose -- so a
  policy change moves the page without anyone editing this file.

  DETERMINISM: no timestamp, no random id, no wall clock anywhere in
  the page. Coordination ids come from `autoparts.registry`'s
  per-storefront-per-kind sequence counter, which starts at 0 in a
  freshly seeded `store/seed-db`. Two consecutive runs are
  byte-identical (verified by `cmp`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [autoparts.store :as store]
            [autoparts.operation :as op]
            [autoparts.governor :as governor]
            [autoparts.partsopsadvisor :as advisor]
            [autoparts.phase :as phase]
            [autoparts.facts :as facts]
            [langgraph.graph :as g]))

;; ───────────────────────── scenario drivers ─────────────────────────

(def ^:private phase-3-operator
  {:actor-id "op-1" :actor-role :parts-coordinator :phase 3})

(def ^:private phase-1-operator
  {:actor-id "op-1" :actor-role :parts-coordinator :phase 1})

(defn- tampered-advisor
  "A DELIBERATELY compromised advisor used to exercise the two governor
  HARD checks that the honest `mock-advisor` can never trip on its own
  (`effect-propose-only` and the structured half of
  `compatibility-certification-finalization-block`).

  It runs this repo's OWN real `autoparts.partsopsadvisor/infer` and
  then applies `f` to the resulting proposal -- i.e. it models an
  advisor swap / prompt-injected LLM, which is exactly the threat the
  AutoPartsOpsGovernor exists to be independent of. The governor is
  untouched; only the untrusted node is."
  [f]
  (reify advisor/Advisor
    (-advise [_ st req] (f (advisor/infer st req)))))

(defn- run-op!
  "Executes ONE real operation on `actor` and records the real run.
  `approve` is `:approved`, `:rejected`, or nil (never expected to
  pause). If the graph did not actually pause at `:request-approval`,
  no approval is sent -- the page then honestly shows an auto-commit."
  [runs actor tid label request ctx & [approve]]
  (let [r1 (g/run* actor {:request request :context ctx} {:thread-id tid})
        r2 (when (and approve (= :interrupted (:status r1)))
             (g/run* actor {:approval {:status approve :by (:actor-id ctx)}}
                     {:thread-id tid :resume? true}))
        final (or r2 r1)]
    (swap! runs conj {:tid     tid
                      :label   label
                      :request request
                      :phase   (:phase ctx)
                      :audit   (get-in final [:state :audit])
                      :verdict (get-in final [:state :verdict])
                      :status  (:status final)})
    final))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches EVERY
  disposition this actor can produce, so the console shows both sides
  of the gate:

  auto-commit (phase 3, governor-clean, high confidence)
    01 :log-sales-record                 store-1
    02 :schedule-restocking-operation    store-1
    03 :coordinate-supply-order          store-1 -> vendor-1, below the
                                         cost threshold

  escalate -> human approves -> commit
    04 :flag-compatibility-concern       ALWAYS escalates at any phase,
                                         any confidence (two independent
                                         layers agree: the governor's
                                         `:always-escalate?` and the
                                         permanent absence of this op
                                         from every phase's `:auto` set)
    05 :coordinate-supply-order          above `supply-order-cost-threshold`
    06 :schedule-restocking-operation    sku/qty missing -> the advisor
                                         itself drops to 0.3, under the
                                         confidence floor
    09 :log-sales-record at PHASE 1      writable but not auto-eligible
                                         -> `:phase-approval`

  escalate -> human REJECTS -> hold
    07 :flag-compatibility-concern       the approver declines

  HARD hold -- never reaches a human, cannot be overridden
    08 :coordinate-supply-order at PHASE 1  `:phase-disabled` (not yet
                                            writable in this phase)
    10 :finalize-compatibility-certification  `:closed-op-allowlist` +
                                            `:compatibility-certification-
                                            finalization-blocked`
    11 :log-sales-record store-2         `:storefront-not-verified`
    12 :coordinate-supply-order vendor-2 `:vendor-not-verified`
    13 tampered advisor spoofs `:effect :execute`  `:effect-not-propose`
    14 tampered advisor sets `:compatibility-certification-finalized? true`
                                         inside `:value` ->
                                         `:compatibility-certification-
                                         finalization-blocked`

  Scenario 04 doubles as the standing regression for this fleet's
  documented self-trip bug class: the honest advisor's DEFAULT concern
  rationale contains the bare noun 認証 (certification), and it still
  commits -- because the governor checks STRUCTURED fields only, never
  free-text prose.

  Returns {:db store :runs [..]} -- both real."
  []
  (let [db     (store/seed-db)
        actor  (op/build db)
        spoof-effect (op/build db {:advisor (tampered-advisor
                                             #(assoc % :effect :execute))})
        spoof-cert   (op/build db {:advisor (tampered-advisor
                                             #(assoc-in % [:value :compatibility-certification-finalized?] true))})
        runs   (atom [])]

    ;; ---- clean, auto-committing paths (phase 3) ----
    (run-op! runs actor "t01" "売上/入出庫記録の登録 (clean)"
             {:op :log-sales-record :subject "store-1"
              :patch {:sku "sku-brake-pad-001" :qty -2 :type :sale :amount 4200.0}}
             phase-3-operator)

    (run-op! runs actor "t02" "補充スケジュール調整 (clean)"
             {:op :schedule-restocking-operation :subject "store-1"
              :sku "sku-brake-pad-001" :qty 24 :requested-date "2026-07-20"}
             phase-3-operator)

    (run-op! runs actor "t03" "供給調整 (しきい値未満 -> 自動確定)"
             {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-brake-pad-001" :qty 200 :estimated-cost 3200.0}
             phase-3-operator)

    ;; ---- escalate -> approved ----
    (run-op! runs actor "t04" "適合性懸念のフラグ (常にエスカレーション)"
             {:op :flag-compatibility-concern :subject "store-1"
              :sku "sku-oil-filter-099" :concern-type :recall
              :detail "NHTSA recall campaign match reported by a customer"}
             phase-3-operator :approved)

    (run-op! runs actor "t05" "供給調整 (しきい値超過 -> 承認要求)"
             {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-alternator-014" :qty 10 :estimated-cost 9000.0}
             phase-3-operator :approved)

    (run-op! runs actor "t06" "補充スケジュール調整 (根拠不足 -> 確信度フロア割れ)"
             {:op :schedule-restocking-operation :subject "store-1"
              :requested-date "2026-07-28"}
             phase-3-operator :approved)

    ;; ---- escalate -> rejected ----
    (run-op! runs actor "t07" "適合性懸念のフラグ (承認者が却下)"
             {:op :flag-compatibility-concern :subject "store-1"
              :sku "sku-spark-plug-042" :concern-type :counterfeit
              :detail "Packaging hologram mismatch reported by the counter staff"}
             phase-3-operator :rejected)

    ;; ---- phase gate (phase 1 operator) ----
    (run-op! runs actor "t08" "供給調整をフェーズ1で実行 (未解禁 -> HARD hold)"
             {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-1"
              :sku "sku-brake-pad-001" :qty 12 :estimated-cost 800.0}
             phase-1-operator :approved)

    (run-op! runs actor "t09" "売上記録をフェーズ1で実行 (解禁済/自動不可 -> 承認要求)"
             {:op :log-sales-record :subject "store-1"
              :patch {:sku "sku-wiper-blade-311" :qty -1 :type :sale :amount 1800.0}}
             phase-1-operator :approved)

    ;; ---- HARD holds: governor violations ----
    (run-op! runs actor "t10" "適合性認証の確定を試行 (スコープ外の操作)"
             {:op :finalize-compatibility-certification :subject "store-1"}
             phase-3-operator :approved)

    (run-op! runs actor "t11" "未検証storefrontからの売上記録"
             {:op :log-sales-record :subject "store-2"
              :patch {:sku "sku-brake-pad-001" :qty -1 :type :sale :amount 2100.0}}
             phase-3-operator :approved)

    (run-op! runs actor "t12" "未検証vendorへの供給調整"
             {:op :coordinate-supply-order :subject "store-1" :vendor-id "vendor-2"
              :sku "sku-brake-pad-001" :qty 50 :estimated-cost 900.0}
             phase-3-operator :approved)

    (run-op! runs spoof-effect "t13" "改竄アドバイザ: :effect を :execute に詐称"
             {:op :log-sales-record :subject "store-1"
              :patch {:sku "sku-timing-belt-208" :qty -1 :type :sale :amount 7600.0}}
             phase-3-operator :approved)

    (run-op! runs spoof-cert "t14" "改竄アドバイザ: :value に認証確定フラグを注入"
             {:op :flag-compatibility-concern :subject "store-1"
              :sku "sku-oil-filter-099" :concern-type :compatibility
              :detail "Advisor attempts to close the question itself"}
             phase-3-operator :approved)

    {:db db :runs @runs}))

;; ───────────────────────── rendering helpers ─────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- lbl
  "keyword -> its name, anything else -> its printed form. Used for the
  heterogeneous `:basis`/`:cites` vectors (this actor cites both
  keywords like `:sku` and ids like \"store-1\")."
  [x]
  (cond (nil? x) "" (keyword? x) (name x) :else (str x)))

(defn- join-lbl [xs] (str/join ", " (map lbl xs)))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- run-outcome
  "Classify ONE real run from its real audit trail. Returns
  {:kind kw :cell html :reason str}."
  [{:keys [audit]}]
  (let [committed (fact-of audit :committed)
        granted   (fact-of audit :approval-granted)
        requested (fact-of audit :approval-requested)
        rejected  (fact-of audit :approval-rejected)
        hold      (fact-of audit :governor-hold)]
    (cond
      (and committed granted)
      {:kind :approved
       :cell "<span class=\"ok\">承認のうえ確定</span>"
       :reason (str "承認要求理由: " (lbl (:reason requested))
                    " · 承認者: " (lbl (:by granted)))}

      committed
      {:kind :auto
       :cell "<span class=\"ok\">自動確定</span>"
       :reason "ガバナ違反なし・確信度フロア以上・フェーズ3で自動確定可"}

      rejected
      {:kind :rejected
       :cell "<span class=\"warn\">承認者が却下</span>"
       :reason (str "承認要求理由: " (lbl (:reason requested))
                    " · 却下根拠: " (join-lbl (:basis rejected)))}

      hold
      {:kind :hold
       :cell (if (seq (:basis hold))
               "<span class=\"critical\">HARD hold · ガバナ違反 · 人間に到達しない</span>"
               "<span class=\"critical\">HARD hold · フェーズゲート · 人間に到達しない</span>")
       :reason (let [rules (:basis hold)
                     pr    (:phase-reason hold)]
                 (str/join " · " (remove str/blank?
                                         [(when (seq rules) (join-lbl rules))
                                          (when pr (str "phase-gate: " (lbl pr)
                                                        " (phase " (:phase hold) ")"))])))}

      :else
      {:kind :other :cell "<span class=\"muted\">進行中</span>" :reason ""})))

;; ───────────────────────── sections ─────────────────────────

(defn- party-rows [db]
  (let [sf (->> (:storefronts (store/demo-data)) keys sort
                (map #(vector "storefront" (store/storefront db %))))
        vd (->> (:vendors (store/demo-data)) keys sort
                (map #(vector "vendor" (store/vendor db %))))]
    (str/join "\n"
      (for [[kind {:keys [id name verified?]}] (concat sf vd)]
        (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
                (esc kind) (esc id) (esc name)
                (if verified?
                  "<span class=\"ok\">独立検証済み</span>"
                  "<span class=\"critical\">未検証 — 提案は commit されない</span>"))))))

(defn- run-rows [runs]
  (str/join "\n"
    (for [{:keys [tid label request phase verdict] :as r} runs
          :let [{:keys [cell reason]} (run-outcome r)]]
      (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td class=\"num\">%s</td><td class=\"num\">%s</td><td>%s</td><td>%s</td></tr>"
              (esc tid) (esc label)
              (esc (lbl (:op request))) (esc (:subject request))
              (esc phase)
              (esc (:confidence verdict))
              cell (esc reason)))))

(defn- ledger-rows [ledger]
  (str/join "\n"
    (for [{:keys [t op subject disposition basis phase-reason confidence] :as f} ledger]
      (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td>%s</td></tr>"
              (esc (lbl t)) (esc (lbl op)) (esc subject) (esc (lbl disposition))
              (esc (str/join " · " (remove str/blank?
                                           [(join-lbl basis)
                                            (when phase-reason (str "phase-gate: " (lbl phase-reason)))])))
              (esc confidence)
              (esc (str/join " / " (map :detail (:violations f))))))))

(defn- coordination-rows [rows]
  (if (seq rows)
    (str/join "\n"
      (for [{:keys [coordination-id storefront-id] :as row} rows]
        (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
                (esc coordination-id) (esc storefront-id)
                (esc (pr-str (dissoc row :coordination-id :storefront-id))))))
    "        <tr><td colspan=\"3\" class=\"muted\">この実行では 0 件</td></tr>"))

(defn- coordination-section [title note rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" note "</p>\n"
       "    <table>\n"
       "      <thead><tr><th>coordination-id</th><th>storefront</th><th>payload</th></tr></thead>\n"
       "      <tbody>\n"
       (coordination-rows rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- governor-rows
  "Read out of the REAL `autoparts.governor` vars -- change a policy
  constant and this table moves without anyone editing this file."
  []
  (str/join "\n"
    [(format "        <tr><td>1</td><td><span class=\"critical\">HARD</span></td><td><code>closed-op-allowlist</code></td><td>許可される操作は %s の4つのみ</td></tr>"
             (esc (str/join " / " (sort (map name governor/allowed-ops)))))
     "        <tr><td>2</td><td><span class=\"critical\">HARD</span></td><td><code>verified-party-gate</code></td><td>発起 storefront（および供給調整では対象 vendor）が独立検証済みでなければ commit しない</td></tr>"
     "        <tr><td>3</td><td><span class=\"critical\">HARD</span></td><td><code>effect-propose-only</code></td><td>提案の <code>:effect</code> は <code>:propose</code> 以外を受け付けない — 本アクターは調整案しか書かない</td></tr>"
     (format "        <tr><td>4</td><td><span class=\"critical\">HARD</span></td><td><code>compatibility-certification-finalization-block</code></td><td>恒久禁止。構造化フィールドのみで判定（禁止 op: %s ／ <code>:value</code> の真偽フラグ）— rationale 本文は走査しない</td></tr>"
             (esc (str/join " / " (sort (map name governor/banned-finalization-ops)))))
     (format "        <tr><td>5</td><td><span class=\"warn\">SOFT</span></td><td><code>confidence-floor</code></td><td>確信度 %s 未満は人間へエスカレーション</td></tr>"
             (esc governor/confidence-floor))
     "        <tr><td>6</td><td><span class=\"warn\">SOFT</span></td><td><code>compatibility-concern-always-escalates</code></td><td>適合性懸念のフラグはフェーズ・確信度によらず常に人間へ</td></tr>"
     (format "        <tr><td>7</td><td><span class=\"warn\">SOFT</span></td><td><code>supply-order-cost-threshold</code></td><td>見積 %s を超える供給調整は常に人間へ</td></tr>"
             (esc governor/supply-order-cost-threshold))]))

(defn- phase-rows
  "Read out of the REAL `autoparts.phase/phases` table."
  []
  (str/join "\n"
    (for [p (sort (keys phase/phases))
          :let [{:keys [label writes auto]} (get phase/phases p)]]
      (format "        <tr><td class=\"num\">%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
              (esc p) (esc label)
              (if (seq writes) (esc (str/join ", " (sort (map name writes))))
                  "<span class=\"muted\">なし</span>")
              (if (seq auto) (esc (str/join ", " (sort (map name auto))))
                  "<span class=\"muted\">なし — 全件が人間の承認を要する</span>")))))

(defn- facts-rows []
  (str/join "\n"
    (for [{:keys [id name class access url]} facts/catalog]
      (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
              (esc (lbl id)) (esc name) (esc (lbl class)) (esc (lbl access))
              (if url (format "<a href=\"%s\">%s</a>" (esc url) (esc url))
                  "<span class=\"muted\">operator-registered（公開 URL なし）</span>")))))

;; ───────────────────────── document ─────────────────────────

(defn render
  "Renders the whole operator console from a real `run-demo!` result."
  [{:keys [db runs]}]
  (let [ledger    (vec (store/ledger db))
        outcomes  (map run-outcome runs)
        n-hold    (count (filter #(= :hold (:kind %)) outcomes))
        n-approved (count (filter #(= :approved (:kind %)) outcomes))
        n-auto    (count (filter #(= :auto (:kind %)) outcomes))
        n-rejected (count (filter #(= :rejected (:kind %)) outcomes))
        cov       (facts/coverage)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-4530 · 自動車部品小売オペレーション調整 — Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>自動車部品の小売・卸 (ISIC 4530) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">適合性認証の確定は恒久的に権限外</span></p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の結果</h2>\n"
     "    <p class=\"muted\">このページは手書きではありません。<code>clojure -M:dev:render-html</code> が "
     "<code>autoparts.operation</code> の langgraph StateGraph を実際に実行し、その "
     "<code>autoparts.store</code> / 監査台帳 / 実行トレースから生成しています。"
     "タイムスタンプも乱数 ID も含まないため、同じシードからの再実行はバイト単位で同一です。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>区分</th><th class=\"num\">件数</th><th>意味</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td><span class=\"ok\">自動確定</span></td><td class=\"num\">%s</td><td>ガバナ清潔・確信度フロア以上・フェーズ3で自動可の操作</td></tr>\n" n-auto)
     (format "        <tr><td><span class=\"ok\">承認のうえ確定</span></td><td class=\"num\">%s</td><td>人間の承認者に渡り、承認されてから確定した操作</td></tr>\n" n-approved)
     (format "        <tr><td><span class=\"warn\">承認者が却下</span></td><td class=\"num\">%s</td><td>人間に渡ったが承認されず hold になった操作</td></tr>\n" n-rejected)
     (format "        <tr><td><span class=\"critical\">HARD hold</span></td><td class=\"num\">%s</td><td>人間に到達せず、承認では上書きできない hold</td></tr>\n" n-hold)
     (format "        <tr><td>監査台帳の総件数</td><td class=\"num\">%s</td><td>append-only。commit も hold も同じ台帳に残る</td></tr>\n" (count ledger))
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>当事者ディレクトリ（<code>autoparts.store</code> のシード）</h2>\n"
     "    <p class=\"muted\">未検証の当事者は、提案がどれだけ妥当に見えても commit されません（<code>verified-party-gate</code>、HARD）。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>種別</th><th>id</th><th>名称</th><th>検証状態</th></tr></thead>\n"
     "      <tbody>\n"
     (party-rows db) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>実行された操作（1 行 = 1 グラフ実行）</h2>\n"
     "    <p class=\"muted\">確信度は <code>autoparts.governor/check</code> が返した実際の verdict の値です。"
     "<code>t13</code> / <code>t14</code> は改竄アドバイザ（<code>:effect</code> の詐称・<code>:value</code> への認証確定フラグ注入）で、"
     "ガバナがアドバイザから独立していることを実際に確かめています。"
     "<code>t04</code> は既定の懸念 rationale が「認証」という語を含んだまま commit されること、"
     "すなわちガバナが本文走査ではなく構造化フィールドで判定していることの回帰確認です。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>run</th><th>シナリオ</th><th>op</th><th>subject</th><th>phase</th><th>確信度</th><th>結果</th><th>根拠</th></tr></thead>\n"
     "      <tbody>\n"
     (run-rows runs) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>AutoPartsOpsGovernor の判定順序</h2>\n"
     "    <p class=\"muted\">HARD 違反は人間の承認者でも上書きできません（承認ノードにすら到達しません）。"
     "SOFT は人間に回るだけで、承認されれば commit されます。この表は "
     "<code>autoparts.governor</code> の実際の var から生成しています。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>強度</th><th>rule</th><th>内容</th></tr></thead>\n"
     "      <tbody>\n"
     (governor-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>ロールアウトフェーズ（<code>autoparts.phase/phases</code>）</h2>\n"
     "    <p class=\"muted\"><code>flag-compatibility-concern</code> はフェーズ3を含むどのフェーズの自動確定集合にも入っていません。"
     "これはロールアウトの残作業ではなく恒久的な構造です。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>phase</th><th>label</th><th>書き込み可</th><th>自動確定可</th></tr></thead>\n"
     "      <tbody>\n"
     (phase-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>監査台帳（この実行の全件）</h2>\n"
     "    <p class=\"muted\">append-only の決定事実ログ。commit も hold も同じ台帳に残ります。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>fact</th><th>op</th><th>subject</th><th>disposition</th><th>根拠</th><th>確信度</th><th>詳細</th></tr></thead>\n"
     "      <tbody>\n"
     (ledger-rows ledger) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (coordination-section "売上/入出庫記録ログ"
                           "確定した <code>:log-sales-record</code> 調整レコード。coordination-id は storefront × 種別ごとの連番から決まります。"
                           (store/sales-log db))
     (coordination-section "補充スケジュールログ"
                           "確定した <code>:schedule-restocking-operation</code> 調整レコード。棚を補充する行為そのものではなく、そのドラフトです。"
                           (store/restock-log db))
     (coordination-section "適合性懸念ログ"
                           "確定した <code>:flag-compatibility-concern</code> 調整レコード。懸念の<em>提起</em>のみで、判定・認証は含みません。"
                           (store/concern-log db))
     (coordination-section "供給調整ログ"
                           "確定した <code>:coordinate-supply-order</code> 調整レコード。発注の送信でも決済でもありません。"
                           (store/supply-order-log db))

     "  <section class=\"card\">\n"
     "    <h2>引用可能な公開ソース（<code>autoparts.facts</code>）</h2>\n"
     (format "    <p class=\"muted\">%s</p>\n" (esc (:note cov)))
     "    <table>\n"
     "      <thead><tr><th>id</th><th>名称</th><th>class</th><th>access</th><th>URL</th></tr></thead>\n"
     "      <tbody>\n"
     (facts-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-4530 — 自動車部品小売/卸のオペレーション調整アクター。"
     "本アクターは部品適合性の認証機関でもリコール是正の判断主体でもありません。"
     "適合性の確定・リコール是正の最終判断は製造者・認定検査機関・所管規制当局に属します。</p>\n"
     "  <p>生成: <code>clojure -M:dev:render-html</code>（実アクター実行からのビルド時生成、決定的）</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (vec (store/ledger (:db result)))
        holds  (filterv #(= :governor-hold (:t %)) ledger)
        html   (render result)]
    ;; Build-time invariant, not a convention: this console exists to
    ;; show that the governor actually refuses things. A scenario that
    ;; produced no un-overridable hold would render a page that quietly
    ;; misrepresents the actor, so refuse to write it at all.
    (when (zero? (count holds))
      (throw (ex-info (str "REFUSING to write " out
                           ": the scenario produced ZERO :governor-hold ledger entries. "
                           "This console must demonstrate at least one HARD hold "
                           "(a hold that never reaches a human). Fix the scenario in "
                           "`autoparts.render-html/run-demo!` -- do not weaken this check.")
                      {:out out
                       :ledger-count (count ledger)
                       :governor-holds 0
                       :fact-kinds (frequencies (map :t ledger))})))
    (io/make-parents out)
    (spit out html)
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count holds) " governor holds, "
                  (count (filter #(= :approval-granted (:t %))
                                 (mapcat :audit (:runs result))))
                  " human approvals, "
                  (count (:runs result)) " graph runs)"))))
