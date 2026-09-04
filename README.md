# PocketFM

RTL-SDR ドングルで FM 放送を受信する Android アプリ。

[PocketRadar](https://github.com/ayakix/PocketRadar) (ADS-B 受信機) の rtl_tcp
まわりの設計をそのまま流用し、復調部だけ ADS-B の PPM から FM の位相判別に
差し替えたもの。ハードウェアは PocketRadar と共通。

## 全体構成

```
[FM アンテナ] --coax--> [RTL-SDR Blog V4] --USB OTG--> [Android]
                                                          |
                                                          +-- SDR driver アプリ
                                                          |   (marto.rtl_tcp_andro)
                                                          |   USB 権限と R828D 初期化を担当
                                                          |   127.0.0.1:14423 に rtl_tcp を公開
                                                          |
                                                          +-- PocketFM
                                                              |  TCP I/Q リーダ
                                                              |  FM 復調 (位相判別)
                                                              |  AudioTrack 再生
                                                              |  Compose UI
```

PocketFM は **USB デバイスを直接触らない**。USB 権限とチップ初期化は
「SDR driver」アプリに任せ、localhost の TCP ソケットから生 I/Q を受け取る。

ドライバは常駐サーバではなく、`iqsrc://-a 127.0.0.1 -p 14423 -s 1200000` の
VIEW インテントを受けて初めて `rtl_tcp` を起動する。この往復が Android の
USB 権限ダイアログを出す唯一の手段でもあるため、受信開始のたびにインテントを
撃つ必要がある。**クライアントが切断するとドライバは `rtl_tcp` ごと終了する**
ので、一度止めたら次はインテントからやり直しになる。

## 信号処理

```
 u8 I/Q  1.2 MS/s                     ← rtl_tcp から
   │ 1. 複素数化              (b - 127.5) / 127.5
   │ 2. 複素ローパス + 1/5 間引き   カットオフ 100 kHz  →  240 kHz
   │ 3. 位相判別器            d[n] = arg(z[n] · conj(z[n-1]))
   │ 4. DC 除去               同調ずれによる直流分を抜く
   │ 5. 実数ローパス + 1/5 間引き   カットオフ 15 kHz   →   48 kHz
   │                          19 kHz パイロットと 38 kHz ステレオ副搬送波も
   │                          ここで落ちる (現状はモノラルのみ)
   │ 6. デエンファシス        1 次 IIR、50 μs (日本・欧州)
   │ 7. 音量 + クリップ
 Int16 PCM  48 kHz                    → AudioTrack へ
```

FM は周波数に情報を載せるので、1 サンプルあたりの位相変化 (= 瞬時周波数) を
取り出せばそれが復調出力になる。`arg(z[n] · conj(z[n-1]))` はその素直な実装。

### なぜ 1.2 MS/s で受けるのか

RTL-SDR が受け付けるサンプルレートは 225001–300000 Hz と 900001–3200000 Hz の
2 帯だけ。240 kHz を直接指定することもできるが、RTL2832U は低レートだと内部
デシメーションの段数が減って量子化ノイズが乗りやすい。そこで 1.2 MS/s で受けて
ソフト側で 1/5 に落としている (`rtl_fm` のオーバーサンプリングと同じ考え方)。

CPU が厳しければ `FmDemodulator` のコンストラクタ引数で 240 kHz 直結に
落とせる。その場合はフロントエンドの複素フィルタが不要になる。

## モジュール

| モジュール | 役割 | ハードウェア |
|---|---|---|
| **`:fm-radio`** | 純 Kotlin/JVM ライブラリ。rtl_tcp クライアント + FM 復調器。JUnit でテスト可能。 | テストには不要 |
| **`:app`** | Android UI (Compose)、フォアグラウンドサービス、AudioTrack 再生。 | 実受信には一式必要 |

`:fm-radio` を Android から独立させているのは、復調器を JVM 上のユニット
テストで検証できるようにするため。既知のトーンで FM 変調した I/Q を合成し、
復調して同じトーンが出てくることを確かめている (`FmDemodulatorTest`)。

```sh
./gradlew :fm-radio:test
```

## ハードウェア

PocketRadar と同一。ただしアンテナは FM 帯 (76–95 MHz) 向けのものが望ましい。

| 項目 | 内容 |
|---|---|
| 受信機 | RTL-SDR Blog V4 (R828D チューナ) |
| アンテナ | FM 帯用ホイップ、または付属のテレスコピックアンテナ |
| USB OTG | Type-C オス - USB-A メス |
| 給電付き OTG ケーブル / ハブ | **強く推奨。** V4 は約 280–300 mA 引くので、バスパワー OTG では不安定な端末が多い。 |

## ソフトウェア前提

### Android 側
- Android 14 以降 (`minSdk = 34`)
- **SDR driver** アプリのインストール
  - Google Play: <https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro>
  - パッケージ名: `marto.rtl_tcp_andro`
  - ソース: <https://github.com/martinmarinov/rtl_tcp_andro->
  - 手動設定は不要。PocketFM の「受信開始」を押すと起動する。USB 権限
    ダイアログが出たら許可すること。
  - Android は USB 権限を **uid** 単位で付与するため、ドライバを再インストール
    すると無言で失効する。その場合は素の connection refused になる。

### ビルド環境
- Android Studio 最新安定版、Kotlin 2.1.x、AGP 8.13
- Gradle 8.14、JDK 17 (Foojay ツールチェーンリゾルバが自動取得)
- Jetpack Compose (BOM 2026.04.01)、Material 3
- DI フレームワークなし。`ViewModelProvider.Factory` で直接渡す。

`local.properties` に SDK の場所を書く (Android Studio が自動生成する)。

```properties
sdk.dir=/Users/<user>/Library/Android/sdk
```

## 使い方

1. 給電付き OTG ケーブル経由で RTL-SDR を Android 端末に接続する
2. PocketFM を起動する
3. 周波数を選ぶ (スライダー、± ボタン、プリセット)
4. **受信開始** を押す → SDR driver が起動 → USB 権限を許可
5. 再生中でも選局・音量・ゲインは反映される (ソケットは張り直さない)

ノイズが多いときはゲインを上げ、音が割れるときは下げる。FM 放送は信号が強い
ので最大ゲインは不要で、上げすぎると前段が飽和してかえって歪む。

## 現状の制限と今後

- **モノラルのみ。** ステレオには 19 kHz パイロットの検出と 38 kHz 副搬送波
  からの L−R 復元が必要。240 kHz の MPX 信号は既に取れているので、
  設計上の障害はない。
- **RDS 未対応。** 57 kHz 副搬送波。240 kHz サンプリングのナイキスト
  (120 kHz) 内には収まっているので、こちらも後付けできる。
- **AM (中波) 未対応。** V4 の HF 受信は direct sampling が必要で、
  marto ドライバ側の librtlsdr が V4 対応版かどうか未検証。
- **プリセットが東京固定。** 利用者が編集できるようにしたい。
- 周波数補正 (ppm) の UI がない。`RtlTcpClient.setFrequencyCorrectionPpm` は
  実装済みなので繋ぐだけ。

## ライセンス

MIT
