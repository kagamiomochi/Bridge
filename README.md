# Bridge

LinuxとAndroidをつなぐ連携ソフトです。現在の機能はクリップボード同期のみで、機能追加はプラグインとして足せる設計になっています。

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
- 任意: `python-zeroconf` (mDNS による自動検出。無くてもデーモンは動きますが、手動でアドレスを指定することになります)
- 任意: `libnotify` (`notify-send`。ペアリングコードをデスクトップ通知で表示します)

Arch 系の場合:

```
sudo pacman -S python-websockets python-zeroconf wl-clipboard openssl libnotify
```

Android:

- Android 8.0 (API 26) 以上
- [Shizuku](https://shizuku.rikka.app/) がインストールされ、起動していること
- ビルドには Android Studio を使います

## セットアップ

### 1. Linux側

```
mkdir -p ~/.local/bin
cp linux/bridge_daemon.py ~/.local/bin/
chmod +x ~/.local/bin/bridge_daemon.py
```

手動で試すだけなら、次のように実行できます。

```
~/.local/bin/bridge_daemon.py
```

常駐させる場合は systemd のユーザーユニットとして登録します。

```
mkdir -p ~/.config/systemd/user
cp linux/bridge.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now bridge
```

systemd 経由で起動する場合、`wl-paste` が Wayland のソケットを見つけられるよう、`WAYLAND_DISPLAY` がユーザーマネージャの環境に入っている必要があります。Hyprland の設定で、起動時に次を実行してください。

```
dbus-update-activation-environment --systemd WAYLAND_DISPLAY
```

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

### 2. Android側

1. `android/` を Android Studio で開き、Gradle の同期が終わるのを待ちます (Gradle wrapper は同梱していないので、IDE が生成します)。
2. 実機にインストールします。
3. Shizuku を起動します (ワイヤレスデバッグ、または ADB で起動)。
4. アプリを開いて「Grant Shizuku permission」を押し、許可します。

### 3. ペアリング

1. デーモンを初めて起動すると、ペアリングの受付が自動で始まります。`XXXX-XXXX` 形式のコードがデスクトップ通知とログに出ます (systemd の場合は `journalctl --user -u bridge`)。
2. アプリで「Pair new device」を押すと、LAN内のデバイスが一覧に出ます。
3. 自分の Linux を選び、コードを入力します。
4. ペアリングが済むと、自動で接続が始まります。

2台目以降の端末を追加するときは、Linuxで次を実行して、新しいコードを出します。

```
bridge_daemon.py --pair
```

コードは1回限りで、2分で失効します。3回間違えると無効になります。

mDNS が届かない環境では、アプリのホスト欄に `192.168.x.x` または `192.168.x.x:8765` を入れてから「Pair new device」を押すと、一覧を出さずにそのアドレスへ直接ペアリングできます。

## 管理コマンド

```
bridge_daemon.py --pair         ペアリングの受付を2分間開き、コードを表示する
bridge_daemon.py --devices      ペアリング済みの端末を一覧表示する
bridge_daemon.py --revoke ID    端末を取り消す (その端末は接続できなくなる)
```

## 設定とデータ

すべて `~/.config/bridge/` に保存されます。

| ファイル | 内容 |
| --- | --- |
| `config.toml` | 待ち受けアドレス (`host`) とポート (`port`) |
| `cert.pem`, `key.pem` | 初回起動時に作られる自己署名証明書と秘密鍵 |
| `devices.json` | ペアリング済み端末。トークン本体ではなくハッシュだけを保存 |

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

### 自動再接続

接続が切れても、Android側が自動でつなぎ直します。

- 切断後は 1秒 → 2秒 → 4秒 → 8秒 → 16秒 → 30秒 と間隔を延ばしながら再試行します。30秒を上限にそのまま続けます。10秒以上つながっていた接続が切れた場合は、1秒後の再試行からやり直します。
- Wi-Fi の切断・再接続や、Wi-Fi とモバイル通信の切り替えを検知すると、待たずにすぐ再接続します。
- 接続に失敗すると保存済みのアドレスを捨て、mDNS でもう一度探します。Linux側のIPアドレスが変わっても追従できます。
- Linux側は IP アドレスの変化を10秒ごとに確認し、変わったら mDNS の広告を更新します (Wi-Fi の再接続やスリープ復帰のあとなど)。
- サーバーから閉じられたときは、Android側でも閉じる手順を完了させるので、切断をすぐ検知できます。

アプリのトップ画面の `Next retry` に、次の再試行までの待ち時間が出ます。

### スマホ再起動後の自動起動

スマホを再起動すると、ロック解除後に同期サービスが自動で起動します (アプリを更新したあとも同様です)。ペアリング済みで、アプリの「Stop」を押していない場合だけ起動します。「Stop」を押すと自動起動も止まり、「Start」を押すと戻ります。

- サービスは、Shizuku が起動するまで数秒おきに待ち続けます。ただし Shizuku 自体は再起動後に自動では起動しないことが多いので、Shizuku アプリから起動してください (Android 13 以降では、Shizuku のワイヤレスデバッグによる自動起動の設定が使えます)。
- 機種によっては、アプリの「自動起動」を許可しないと起動時の処理が動きません (OPPO / ColorOS、Xiaomi など)。動かない場合は、設定でこのアプリの自動起動を許可し、バッテリーの最適化から除外してください。

## 機能の追加 (プラグイン)

Linux側は `Plugin` を継承して `hub.register(...)` で登録します。

```python
class NotifyPlugin(Plugin):
    prefix = "notify"  # owns all "notify.*" messages

    async def on_message(self, ws, type_, data):
        if type_ == "notify.show":
            await notify(data.get("title", ""), data.get("body", ""))

# in main():
hub.register(NotifyPlugin())
```

Android側は `BridgePlugin` を実装して、`SyncService` の `plugins +=` に足します。

```kotlin
class NotifyPlugin : BridgePlugin {
    override val prefix = "notify"

    override fun onConnected(sender: Sender) {
        // Send messages with sender.send("notify.show", JSONObject()...)
    }

    override fun onMessage(type: String, data: JSONObject) {
        // Handle messages from Linux
    }
}

// in SyncService.onCreate():
plugins += NotifyPlugin()
```

## トラブルシューティング

アプリのトップ画面に、状態が4行で出ます。

| 表示 | 意味と対処 |
| --- | --- |
| `Shizuku: not running` | Shizuku が起動していません。Shizuku アプリから起動してください |
| `Shizuku: permission not granted` | 「Grant Shizuku permission」を押して許可してください |
| `Shizuku service: not bound` | 許可済みなら数秒で接続されます。いつまでも変わらない場合は、`adb logcat -s BridgeClip` でエラーを確認してください |
| `Connection: not paired` | ペアリングが済んでいません |
| `Connection: searching on the local network` | mDNS で探しています。見つからない場合は UDP 5353 とネットワーク (APアイソレーション) を確認してください。手動でアドレスを指定する方法もあります |
| `Connection: failed: ...` | Linuxに届いていません。TCP 8765 とデーモンの起動を確認してください |
| `Connection: closed (4401 ...)` | 端末が取り消されたか、ペアリング情報が一致しません。再ペアリングしてください |

Linux側の動作は、デーモンのログ (`journalctl --user -u bridge`) で確認できます。クリップボードが変わるたびに `local clipboard changed` と `received clipboard from client` が出ます。

## 制限事項

- Linux側は Wayland (wl-clipboard) のみ対応です。
- クリップボードはテキストのみで、最大1 MiB です。
- スマホが記憶できるペアリング先は1台です (Linux側は複数の端末を登録できます)。
- Android の内部クリップボード API はバージョンによって引数が違うため、型から引数を組み立てています。Android 14 以降の端末では、特に動作を確認してください。
- 通信は IPv4 のみを想定しています。
