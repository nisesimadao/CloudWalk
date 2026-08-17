<p align="center">
  <img src="assets/banner.svg" alt="CloudWalk — 軽量ネイティブAndroid SoundCloudクライアント" width="100%">
</p>

<p align="center">
  日本語 · <a href="README.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/nisesimadao/CloudWalk/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/nisesimadao/CloudWalk?style=flat-square&color=ff6a00"></a>
  <a href="https://github.com/nisesimadao/CloudWalk/actions"><img alt="Android build" src="https://img.shields.io/github/actions/workflow/status/nisesimadao/CloudWalk/android.yml?branch=main&style=flat-square&label=build"></a>
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="English and Japanese" src="https://img.shields.io/badge/UI-English%20%7C%20Japanese-555?style=flat-square">
  <img alt="No WebView" src="https://img.shields.io/badge/UI-Native%20Views-ff6a00?style=flat-square">
</p>

# CloudWalk

CloudWalkは、**とにかく軽いネイティブAndroid向けSoundCloudクライアント**です。起動の速さ、少ないメモリ使用量、古めのAndroid端末での使いやすさを重視しています。WebViewや重いUIスタックではなく、Android Viewsと標準メディアAPIを中心に作っています。

<p align="center">
  <a href="https://github.com/nisesimadao/CloudWalk/releases/latest"><b>最新APKをダウンロード</b></a>
</p>

## できること

- SoundCloudの公開曲を検索・再生
- 公開プロフィールURLからLikes / uploadsをログインなしで取り込み
- キューを視覚的にめくれるCover FlowとネイティブなNow Playing画面
- キュー、並べ替え、シャッフル、リピート
- MediaSession対応のバックグラウンド再生
- 同じセッション中の再ダウンロードを減らす一時キャッシュ
- 端末内のローカル音源も同じプレイヤーで再生
- 日本語 / English UI
- Android 8.0（API 26）以上

## スクリーンショット

<table>
  <tr>
    <td align="center"><b>Home / Cover Flow</b></td>
    <td align="center"><b>Search</b></td>
    <td align="center"><b>Now Playing</b></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/home.png" width="260" alt="CloudWalk Home"></td>
    <td><img src="docs/screenshots/search.png" width="260" alt="CloudWalk Search"></td>
    <td><img src="docs/screenshots/now-playing.png" width="260" alt="CloudWalk Now Playing"></td>
  </tr>
  <tr>
    <td align="center"><b>Queue</b></td>
    <td align="center"><b>Library</b></td>
    <td></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/queue.png" width="260" alt="CloudWalk Queue"></td>
    <td><img src="docs/screenshots/library.png" width="260" alt="CloudWalk Library"></td>
    <td></td>
  </tr>
</table>

> 上の画像は、Android 9の小画面エミュレータで実際に動かしたCloudWalkのスクリーンショットです。

## Build

```sh
./gradlew assembleDebug
```

ReleaseはR8 / resource shrinkingを使います。

```sh
./gradlew assembleRelease
```

## 注意

CloudWalkは実験的なプロジェクトで、**SoundCloud公式アプリではありません**。公開曲の検索・再生は現在SoundCloudの公開Webクライアントの挙動に追従しているため、SoundCloud側の変更で一時的に動かなくなることがあります。

セッションキャッシュは一時保存で、CloudWalk終了時に削除されます。SoundCloudの保護されたストリームは、メディア本体がキャッシュ済みでも再生ライセンス取得のために通信が必要な場合があります。
