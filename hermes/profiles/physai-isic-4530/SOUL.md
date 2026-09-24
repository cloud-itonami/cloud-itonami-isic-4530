# physai-isic-4530 — 自動車部品販売業（ISIC 4530）の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-4530`、ISIC 4530 自動車部品・付属品の販売）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 自律倉庫ピッキング／補充ロボットが、この actor が調整する補充作業を物理的に行う。
その物理的な仕事（補充アームが部品箱（ブレーキローター・バッテリー）を棚に収める、AMR が高く積んだトートを通路で運び非常停止する）を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:put-away-parts-box` | manipulator | 補充アームがトートから箱入り部品を持ち上げて棚のビンに収める（部品質量を掃引） | 肩関節ピークトルク | ≤ 180 N·m（estimate） |
| `:tote-tower-amr-estop` | transport | 部品トート 60 kg を積んだ AMR が通路を走り 2 m/s² で非常停止する（積荷の重心高さを掃引） | 最小転倒余裕 | ≥ 0.5（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/autoparts/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。この repo 自身の `test/` の `.cljk` も同じ runner で走る: 合計 26 tests / 120 assertions）。

## 測って分かったこと・限界（成長の第一候補）

1. **部品の棚入れ**: 肩トルクは 2 kg で 72.2 N·m、10 kg で 133.5、15 kg で 172.1、22 kg で 226.1 N·m。限界 180 N·m を越えるのは **約 16.0 kg**。スターターバッテリー（〜20 kg）はこのアームでは載せられない。
2. **トートタワー AMR**: 最小転倒余裕は重心 0.6 m で 0.728、1.2 m で 0.592、1.5 m で 0.524、1.8 m で 0.456。限界 0.5 を割るのは **重心 約 1.61 m**。停止距離は 0.5625 m。
3. **estimate のままの値**: 肩トルク 180 N·m（協働アームの仕様書）、転倒余裕 0.5（AMR メーカーの積載条件・ISO 3691-4）、非常停止減速度 2 m/s²（AMR の仕様書）。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-4530 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-4530 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
