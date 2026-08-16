# CloudWalk

[日本語](#日本語) / [English](#english)

## 日本語

CloudWalkは、**とにかく軽いAndroid向けSoundCloudクライアント**を作るプロジェクトです。

WebViewや重いUIフレームワークを使わず、AndroidのネイティブViewと標準メディアAPIを中心に作っています。Cover Flow、検索、キュー、ローカル音源、セッションキャッシュなどを小さいアプリにまとめています。

まだ実験的です。SoundCloudのWeb側の変更で公開曲の検索・再生が壊れることがあります。SoundCloud公式アプリではありません。

### Build

```sh
./gradlew assembleDebug
```

Android 8.0 (API 26) 以上。

APK: [GitHub Releases](https://github.com/nisesimadao/CloudWalk/releases)

## English

CloudWalk is a project for building a **very lightweight SoundCloud client for Android**.

It uses native Android Views and platform media APIs instead of a WebView or a heavy UI stack. Cover Flow, search, queues, local audio and session caching are kept in a small app.

It is still experimental. Public-track search/playback may break when SoundCloud changes its web client. CloudWalk is not an official SoundCloud app.

### Build

```sh
./gradlew assembleDebug
```

Android 8.0 (API 26) or newer.

APK: [GitHub Releases](https://github.com/nisesimadao/CloudWalk/releases)
