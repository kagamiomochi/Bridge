# Bridge

LinuxとAndroidをつなぐ連携ソフトです。現在の機能はクリップボード同期、再生中メディアの同期、通知の同期 (双方向) で、機能追加はプラグインとして足せる設計になっています。

- 通信は TLS (wss) で暗号化され、ペアリング済みの端末だけが接続できます
- 接続先は mDNS で自動検出されます(IPアドレスの入力は不要)
- Android側は Shizuku 経由でクリップボードを読み書きするので、アプリを開いたりボタンを押したりする必要がありません

## 構成

```
bridge/
  linux/
    bridge_daemon.py   Linux側デーモン (Python)
    bridge.service     systemd ユーザーユニット
  android/             Android アプリ (Kotlin, Gradle プロジェクト)
```

## 動作環境

Linux:

- Wayland 環境 (Hyprland で動作確認の対象にしています。X11 には未対応です)
- Python 3.11 以上
- `python-websockets`
- `wl-clipboard`
- `openssl` (初回起動時の証明書生成に使います)
- 任意: `playerctl` (再生中メディアの同期に使います。無い場合はメディア同期だけが無効になります)
- 任意: `python-pillow` (アルバムアートを縮小して送るために使います。無い場合は、600 KB 以下の画像だけをそのまま送ります)
- 任意: `python-zeroconf` (mDNS による自動検出。無くてもデーモンは動きますが、手動でアドレスを指定することになります)
- 任意: `libnotify` (`notify-send`。ペアリングコードの表示と、スマホの通知の表示に使います)
- 任意: `busctl` (systemd に含まれる D-Bus ツール。Linux の通知をスマホに送るために使います。無い場合はスマホ → Linux 方向だけが有効になります)

Arch 系の場合:

```
sudo pacman -S python-websockets python-zeroconf wl-clipboard playerctl python-pillow openssl libnotify
```

Android:

- Android 8.0 (API 26) 以上
- [Shizuku](https://shizuku.rikka.app/) がインストールされ、起動していること
- ビルドには Android Studio を使います

## セットアップ

### 1. Linux側

ファイアウォールで次のポートを開けてください。

| ポート | 用途 |
| --- | --- |
| TCP 8765 | Bridge の通信 |
| UDP 5353 | mDNS による自動検出 |

例 (ufw):

```
sudo ufw allow 8765/tcp
sudo ufw allow 5353/udp
```

Releasesからダウンロードするか、リポジトリをクローンして`bridge_daemon.py`を実行してください。

```
python bridge_daemon.py
```

常駐させたい場合はHyprlandの`hl.exec_cmd`やSwayの`exec`等、環境にあった方法で自動起動するように設定してください。

例 (Hyprland):
```
hl.on("hyprland.start", function()
    hl.exec_cmd("python ~/Bridge/linux/bridge_daemon.py")
end)
```

### 2. Android側

1. `android/` を Android Studio で開き、Gradle の同期が終わるのを待ちます (Gradle wrapper は同梱していないので、IDE が生成します)。
2. 実機にインストールします。
3. Shizuku を起動します (ワイヤレスデバッグ、または ADB で起動)。
4. アプリを開いて「Grant Shizuku permission」を押し、許可します。

### 3. ペアリング

1. デーモンを初めて起動すると、ペアリングの受付が自動で始まります。`XXXX-XXXX` 形式のコードがデスクトップ通知とログに出ます (systemd の場合は `journalctl --user -u bridge`)。
2. アプリで「Pair new device」を押すと、ホスト欄とLAN内のデバイス一覧のダイアログが出ます。
3. 自分の Linux を選び、コードを入力します。
4. ペアリングが済むと、自動で接続が始まります。

2台目以降の端末を追加するときは、Linuxで次を実行して、新しいコードを出します。

```
bridge_daemon.py --pair
```

コードは1回限りで、2分で失効します。3回間違えると無効になります。

mDNS が届かない環境では、アプリで「Pair new device」を押し、ダイアログ内のホスト欄に `192.168.x.x` または `192.168.x.x:8765` を入れて「Pair」を押すと、一覧を使わずにそのアドレスへ直接ペアリングできます。

## 管理コマンド

```
bridge_daemon.py --pair         ペアリングの受付を2分間開き、コードを表示する
bridge_daemon.py --devices      ペアリング済みの端末を一覧表示する
bridge_daemon.py --revoke ID    端末を取り消す (その端末は接続できなくなる)
bridge_daemon.py --media        スマホで再生中の曲を表示する
bridge_daemon.py --media-control ACTION
                                スマホのプレイヤーを操作する (play / pause / play_pause / next / previous)
bridge_daemon.py --media-seek SECONDS
                                スマホのプレイヤーの再生位置を変える
```

## 設定とデータ

すべて `~/.config/bridge/` に保存されます。

| ファイル | 内容 |
| --- | --- |
| `config.toml` | 待ち受けアドレス (`host`) とポート (`port`) |
| `cert.pem`, `key.pem` | 初回起動時に作られる自己署名証明書と秘密鍵 |
| `devices.json` | ペアリング済み端末。トークン本体ではなくハッシュだけを保存 |

`$XDG_RUNTIME_DIR/bridge-media.json` には、スマホで再生中の曲の最新状態が、`$XDG_RUNTIME_DIR/bridge-media-art.jpg` にはそのアルバムアートが書き込まれます (後述)。

証明書を作り直すと、すべての端末で再ペアリングが必要になります。

## 仕組み

### 通信

WebSocket 上で、次の形式の JSON メッセージをやりとりします。

```json
{"v": 1, "type": "clipboard.update", "data": {"text": "..."}}
```

`type` の `.` より前の部分 (`clipboard` など) がプラグインの名前で、同じ名前のプラグインにメッセージが渡されます。

| type | 方向 | 用途 |
| --- | --- | --- |
| `pair.request` / `pair.challenge` / `pair.proof` / `pair.ok` / `pair.denied` | 双方 | ペアリング |
| `hello` / `hello.ok` | 双方 | 接続時の認証 (端末ID とトークン) |
| `clipboard.update` | 双方 | クリップボードの内容 (テキスト、最大1 MiB) |
| `media.state` | 双方 | 再生中のメディアの状態 (`status` / `player` / `title` / `artist` / `album` / `duration_ms` / `position_ms` / `art_id`) |
| `media.art` | 双方 | アルバムアート (`id` は `media.state` の `art_id` と対応、`mime`、`data` は Base64。曲ごとに1回送る) |
| `media.control` | 双方 | 相手側のプレイヤーの操作 (`action`: `play` / `pause` / `play_pause` / `next` / `previous` / `seek`。`seek` のときは `position_ms` も付く) |
| `notify.posted` | 双方 | 通知の内容 (`key` / `app` / `title` / `text` / `time`)。同じ `key` の再送は更新として扱う |
| `notify.removed` | 双方 | 通知が消された (`key` に対応する相手側の通知を閉じる) |

### セキュリティ

- 通信は TLS 1.2 以上です。サーバー証明書は自己署名ですが、ペアリング後はスマホが証明書のフィンガープリント (SHA-256) を固定して接続するので、偽のサーバーにはつながりません。
- ペアリングの証明は、コードから PBKDF2 (100,000回) で導出した鍵と、サーバー証明書のフィンガープリントを組み合わせて計算します。ネットワーク上の第三者が別の証明書で割り込むと、証明が合わず失敗します。サーバー側も同じ鍵で証明を返すので、スマホは相手が本物であることを確認できます。
- ペアリング後は端末ごとにランダムなトークンを発行します。Linux側にはそのハッシュだけを保存します。
- ペアリングコードは8文字 (40ビット) で、2分・3回までの試行に制限しています。

信頼できるLAN内での利用を想定しています。ペアリング中にログへ出るのはコードだけで、トークンはログに出ません。

### クリップボード同期

- Linux: `wl-paste --watch` で変更を検知し、`wl-copy` で書き込みます。
- Android: Shizuku の shell 権限プロセス (`ClipboardUserService`) から、内部のクリップボードサービスに直接アクセスします。500ms ごとに確認します。
- 受信した内容を書き戻したときに、同じ内容が送り返されて無限に往復しないよう、内容のハッシュで見分けています。
- 対象はテキストのみです。画像などは同期しません。

### 再生中メディアの同期

両方向で、再生中の曲の状態を送り合います。

- Linux → スマホ: `playerctl` (MPRIS) で再生状態の変化を検知して送ります。スマホでは、Android 標準のメディアコントロール (通知センター、クイック設定のメディアカード、ロック画面) に曲名・アーティスト・アルバムアート・シークバーが出て、前へ / 再生・一時停止 / 次への操作とシークで Linux のプレイヤーを操作できます。アルバムアートは、`file://` と `http(s)://` の URL のものを Linux 側で読み込み、512 px 以下の JPEG にして送ります。Linux側が再生中の間は、Bluetooth イヤホンなどのメディアボタンも Linux のプレイヤーに届きます。
- スマホ → Linux: スマホの再生中のメディアセッション (再生中のものを優先) を送ります。Linux側は最新の状態を `$XDG_RUNTIME_DIR/bridge-media.json` に書き出すので、waybar などのバーやスクリプトから読めます。アルバムアートは `bridge-media-art.jpg` に保存され、状態ファイルの `art_path` で場所が分かります。`bridge_daemon.py --media` で表示、`bridge_daemon.py --media-control next` や `bridge_daemon.py --media-seek 90` などで操作できます。
- Android: メディアセッションの読み取りには通知へのアクセス権 (`BridgeListenerService`) が必要です。Shizuku 経由で自動的に有効にするので、設定画面を開く必要はありません。有効にならない場合は、設定の「通知へのアクセス」で Bridge を手動で許可してください。
- 接続したときに、Linux で再生中なら現在の状態がすぐスマホに送られます。
- 再生位置は、状態が変わるたびに送ります。そのほか、Linux側は 1.5 秒ごとに再生位置を確認し、予想から1.5秒以上ずれていたらシークとみなして再送します。スマホ側も同じ基準でずれを検出します。

### 通知の同期

スマホの通知を Linux に出し、Linux の通知をスマホに出す、双方向の同期です。

- スマホ → Linux: `BridgeListenerService` (通知へのアクセス) で通知の投稿と削除を受け取り、`notify.posted` / `notify.removed` を送ります。Linux 側は `notify-send` で表示し、同じ通知の更新は同じ吹き出しを置き換えます。スマホ側で通知を消す (アプリで開く、スワイプするなど) と、`gdbus` または `dbus-send` でデスクトップ側の吹き出しも閉じます。通知へのアクセスは Shizuku 経由で自動的に有効になるので、設定画面を開く必要はありません。
- Linux 側に送るのは、ユーザーが消せる通知だけです。メディアプレイヤーやダウンロードなどの継続中の通知、グループの要約 (summary) は、重複やノイズを避けるため送りません。メディアは「再生中メディアの同期」で別に扱います。
- Linux → スマホ: `busctl` でセッション bus の `org.freedesktop.Notifications` (`Notify` / `CloseNotification` / `NotificationClosed`) を監視し、デスクトップ通知を Android の通知として表示します。更新は同じ Android 通知を置き換え、デスクトップ側で閉じた通知や期限切れになった通知は Android 側でも消します。
- 両方向とも、同期するのはタイトルと本文だけです。通知の操作ボタン (返信など) やアイコンは同期しません。
- Bridge が表示した通知には D-Bus のヒント (`x-bridge-origin`) を付けて、Linux → スマホ → Linux のように往復しないようにしています。
- 接続していない間に届いた通知は送りません。接続後に届いたものから同期します。

### 自動再接続

接続が切れても、Android側が自動でつなぎ直します。

- 切断後は 1秒 → 2秒 → 4秒 → 8秒 → 16秒 → 30秒 と間隔を延ばしながら再試行します。30秒を上限にそのまま続けます。10秒以上つながっていた接続が切れた場合は、1秒後の再試行からやり直します。
- Wi-Fi の切断・再接続や、Wi-Fi とモバイル通信の切り替えを検知すると、待たずにすぐ再接続します。
- 接続に失敗すると保存済みのアドレスを捨て、mDNS でもう一度探します。Linux側のIPアドレスが変わっても追従できます。
- Linux側は IP アドレスの変化を10秒ごとに確認し、変わったら mDNS の広告を更新します (Wi-Fi の再接続やスリープ復帰のあとなど)。
- サーバーから閉じられたときは、Android側でも閉じる手順を完了させるので、切断をすぐ検知できます。
- Linux側のデーモンが `SIGTERM` / `SIGINT` / `SIGHUP` で止まるとき (PCのシャットダウンやログアウトなど) は、mDNS の広告を取り下げてから、接続中の端末にclose code 1001 (going away) を送って閉じます。Android側はこれを「意図した停止」と判断して再試行をやめ、デーモンが mDNS に再び現れるまで待機します (トップ画面の `Connection` に `Linux shut down, waiting for it to start` と出ます)。待機中にWi-Fiが切り替わったときは、新しいネットワークで探し直します。電源断やネットワーク断のように close code を伴わない切断は、これまでどおり再試行します。

アプリのトップ画面の `Next retry` に、次の再試行までの待ち時間が出ます。

### スマホ再起動後の自動起動

スマホを再起動すると、ロック解除後に同期サービスが自動で起動します (アプリを更新したあとも同様です)。ペアリング済みで、アプリの「Stop」を押していない場合だけ起動します。「Stop」を押すと自動起動も止まり、「Start」を押すと戻ります。

- サービスは、Shizuku が起動するまで数秒おきに待ち続けます。ただし Shizuku 自体は再起動後に自動では起動しないことが多いので、Shizuku アプリから起動してください (Android 13 以降では、Shizuku のワイヤレスデバッグによる自動起動の設定が使えます)。
- 機種によっては、アプリの「自動起動」を許可しないと起動時の処理が動きません (OPPO / ColorOS、Xiaomi など)。動かない場合は、設定でこのアプリの自動起動を許可し、バッテリーの最適化から除外してください。

## 機能の追加 (プラグイン)

Linux側は `Plugin` を継承して `hub.register(...)` で登録します。

```python
class PingPlugin(Plugin):
    prefix = "ping"  # owns all "ping.*" messages

    async def on_message(self, ws, type_, data):
        if type_ == "ping.hello":
            await self.hub.broadcast("ping.hello", {"from": "linux"})

# in main():
hub.register(PingPlugin())
```

Android側は `BridgePlugin` を実装して、`SyncService` の `plugins +=` に足します。

```kotlin
class PingPlugin : BridgePlugin {
    override val prefix = "ping"

    override fun onConnected(sender: Sender) {
        // Send messages with sender.send("ping.hello", JSONObject()...)
    }

    override fun onMessage(type: String, data: JSONObject) {
        // Handle messages from Linux
    }
}

// in SyncService.onCreate():
plugins += PingPlugin()
```

## トラブルシューティング

アプリのトップ画面に状態が出ます。

| 表示 | 意味と対処 |
| --- | --- |
| `Shizuku: not running` | Shizuku が起動していません。Shizuku アプリから起動してください |
| `Shizuku: permission not granted` | 「Grant Shizuku permission」を押して許可してください |
| `Shizuku service: not bound` | 許可済みなら数秒で接続されます。いつまでも変わらない場合は、`adb logcat -s BridgeClip` でエラーを確認してください |
| `Connection: not paired` | ペアリングが済んでいません |
| `Connection: searching on the local network` | mDNS で探しています。見つからない場合は UDP 5353 とネットワーク (APアイソレーション) を確認してください。手動でアドレスを指定する方法もあります |
| `Connection: failed: ...` | Linuxに届いていません。TCP 8765 とデーモンの起動を確認してください |
| `Connection: closed (4401 ...)` | 端末が取り消されたか、ペアリング情報が一致しません。再ペアリングしてください |
| `Media: waiting for listener permission` (Last event) | 通知へのアクセス権がまだ有効になっていません。Shizuku が起動していれば自動で有効になります。ならない場合は設定の「通知へのアクセス」で Bridge を許可してください |
| `Notifications: waiting for listener permission` | 同上。通知の同期に使う通知へのアクセス権がまだ有効になっていません |

Linux の通知がスマホに出ないときは、Android 13 以降で Bridge の通知権限が許可されているか確認してください。`busctl` が無い場合は、デーモンのログに `busctl not found: Linux notifications are not sent to the phone` が出ます。

Linux側の動作は、デーモンのログ (`journalctl --user -u bridge`) で確認できます。クリップボードが変わるたびに `local clipboard changed` と `received clipboard from client`、スマホの通知が届くたびに `notification from ...`、Linux の通知をスマホに送るときに `local notification to phone: ...` が出ます。

## 制限事項

- Linux側は Wayland (wl-clipboard) のみ対応です。メディア同期は MPRIS 対応のプレイヤーが対象です。
- クリップボードはテキストのみで、最大1 MiB です。
- アルバムアートは、スマホ側ではアプリがメディア情報に画像そのものを入れている場合だけ送れます (画像の URL だけを渡すアプリのものは送れません)。Linux側は、プレイヤーが `file://` か `http(s)://` の URL を出している場合に対応します。
- シークの反映には最大で1.5秒ほどかかります。シークに対応していないプレイヤーでは、シークバーを動かしても反映されません。
- 複数のプレイヤーが同時にあるとき、Linux側はどれを送るかを `playerctl` の既定の選び方に任せています (Android側は再生中のものを優先します)。
- スマホが記憶できるペアリング先は1台です (Linux側は複数の端末を登録できます)。
- 通知の同期は双方向ですが、同期するのはタイトルと本文だけです。通知の操作ボタン、アイコン、返信には対応しません。
- スマホ → Linux では、継続中の通知 (メディア、ダウンロード、常駐サービスなど)、グループの要約、ユーザーが消せない通知は送りません。
- Linux → スマホには `busctl` (systemd) と、通知を扱う D-Bus サービスが必要です。無い場合はスマホ → Linux 方向だけが動きます。
- 接続前に出ていた通知は、どちらの方向でも接続後に送り直しません。
- アプリ名は、Android のパッケージ可視性の制限で取得できない場合、パッケージ名のまま表示されます。
- Android の内部クリップボード API はバージョンによって引数が違うため、型から引数を組み立てています。Android 14 以降の端末では、特に動作を確認してください。
- 通信は IPv4 のみを想定しています。
