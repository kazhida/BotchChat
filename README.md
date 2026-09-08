# BotchChat

BotchChat は、Android 端末上で Gemma 4 E2B を使って会話するためのオフラインチャットアプリです。LLM 推論、音声入力、返答の読み上げ、会話履歴の保存を端末内で扱います。

## 主な機能

- Gemma 4 E2B の LiteRT-LM モデルを端末内で実行
- 日本語のオフライン音声認識で入力欄へテキストを追加
- Gemma の返答を受信しながら日本語のオフライン音声で読み上げ
- 返答を Markdown として表示
- 会話履歴を最大 1024 メッセージまで保存
- 保存済み履歴から次回起動時に会話コンテキストを再構成
- 履歴削除時の確認ダイアログ

## 動作要件

- Android 14 以降
- 日本語のオフライン音声認識モデル
- 日本語のオフライン Text-to-Speech 音声
- Gemma 4 E2B の `gemma-4-E2B-it.litertlm`

音声認識と読み上げは、端末にインストール済みのオフライン機能だけを使います。ネットワーク経由の音声認識、オンライン音声、モデルダウンロードへのフォールバックは行いません。

## モデル配置

Gemma 4 E2B のモデルファイルはサイズが大きいため、APK とリポジトリには含めません。利用前に `gemma-4-E2B-it.litertlm` を端末へ配置してください。

アプリをインストールして一度起動した後、開発端末から次のように配置できます。

```sh
adb shell mkdir -p /sdcard/Android/data/com.abplus.botchchat/files/models
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.abplus.botchchat/files/models/gemma-4-E2B-it.litertlm
```

アプリは `Context.getExternalFilesDir("models")` の `gemma-4-E2B-it.litertlm` を読み込みます。外部アプリ領域が使えない場合は、内部ストレージの `files/models` を使います。

詳細は [docs/gemma-4-e2b.md](docs/gemma-4-e2b.md) を参照してください。

## 履歴とコンテキスト

会話履歴はアプリ内部ストレージの `files/chat-history.json` に保存されます。通常の端末上のパスは次のとおりです。

```text
/data/user/0/com.abplus.botchchat/files/chat-history.json
```

保存対象は最大 1024 メッセージです。1024 件を超えた場合は古いメッセージから削除します。起動時にはこの履歴を読み込み、正常に完了した直近のユーザー・アシスタントの会話ペアから推論用コンテキストを再構成します。

保存済み履歴は、開発端末から次のように確認できます。

```sh
adb shell run-as com.abplus.botchchat cat files/chat-history.json
```

## ビルド

このプロジェクトは Gradle Wrapper を使用します。

```sh
./gradlew :app:assembleDebug
```

テストを実行する場合は次のコマンドを使います。

```sh
./gradlew :app:testDebugUnitTest
```

主な構成は次のとおりです。

- Android Gradle Plugin 9.1.0
- Kotlin / Compose plugin 2.2.10
- Gradle 9.6.0
- compileSdk 36
- minSdk 34
- LiteRT-LM 0.13.1
- Markwon 4.6.2

## ライセンス

MIT License
