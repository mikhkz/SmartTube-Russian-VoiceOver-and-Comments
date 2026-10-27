[Русский](README.md) | [English](README_EN.md)

<img src="images/vot/app-logo.png" width="96" height="96" alt="SmartTube VOT logo">

# SmartTube Russian Voice Over & Comments

![Voice-over translation button in the SmartTube VOT player: the arrow points to the headphones icon](images/vot/voice-over-button.png)

Russian video voice-over and translation of comments and replies into Russian
in SmartTube for Android TV. An independent fork with built-in Yandex VOT;
the TV app is named **SmartTube VOT**.

**[Download APK](https://github.com/mikhkz/SmartTube-Russian-VoiceOver-and-Comments/releases/latest)** ·
[Report an issue](https://github.com/mikhkz/SmartTube-Russian-VoiceOver-and-Comments/issues)

Based on **SmartTube 32.68 Stable**. The native translation client has been updated
against **VOT 1.12.3 / vot.js 3.2.0**, with Yandex component **26.10.0.1796**.
This build: **32.68-vot.1**.

## Added features

- Russian voice-over using Yandex standard and lively voices.
- A translation button in the player and optional automatic translation.
- Separate original and translation volume, with voice-over gain up to 150%.
- Synchronization with pause, seeking and playback speed.
- Original audio upload via SABR when Yandex requests audio for translation.
- Translation of foreign-language comments and replies into Russian.

Other SmartTube features, including SponsorBlock, video quality controls and
remote control settings, are retained. This is a complete app with built-in VOT
integration. No browser extension is required.

## Installation

1. Open the [latest release](https://github.com/mikhkz/SmartTube-Russian-VoiceOver-and-Comments/releases/latest).
2. Download `SmartTube_vot_32.68-vot.1_universal.apk`. If you know your device's
   architecture, you can choose a smaller APK: `armeabi-v7a`, `arm64-v8a` or `x86`.
3. Install the APK on your Android TV or TV box and open **SmartTube VOT**.

The app package is `org.smarttube.stable`. Updating a previous build with the same
signature preserves settings. If Android reports a signature conflict, first
back up your settings using SmartTube's backup feature. After uninstalling the
old app and installing VOT, restore that backup.

This build receives updates from this repository's releases.
GitHub Actions APKs are development builds and may use a different signature.

## Russian video voice-over

Open a foreign-language video, press OK on your remote and select the voice-over
translation button. Long-press the button to open volume and automatic translation
settings.

**Settings → Player** provides lively voices and separate volume levels for the
original audio and translation. If the button is hidden, enable **Voice translation**
in the player button settings. The screenshot above shows the Russian UI label
**Закадровый перевод**.

Translation is provided online by Yandex. Preparing a new video may take time;
availability depends on the unofficial API. Lively voices may not be available
for every video. Phones, Tizen and webOS are not supported.

## Comment and reply translation

Open a video's comments using the normal SmartTube button. Foreign-language text
is automatically translated into Russian, including replies within threads and
new comments loaded while scrolling. This feature is enabled by default.

The **Comment translation (RU)** toggle is under **Settings → Player** in the
**Lively voices** group. In the Russian UI, these are **Перевод комментариев (RU)**
and **Живые голоса**. To read the original text, turn the toggle off and reopen
the comments.

Only message text is translated. Author names, avatars, likes and reply structure
are preserved. Text detected as Russian is left unchanged. Results are cached in
the app's memory. If translation fails or the service is unavailable, the original
text is shown; a background retry is provided for remaining untranslated messages.
Machine translation and language detection can make mistakes.

**Yandex VOT** handles **video voice-over**; **Google Translate** handles
**comment text**. These are separate features: comments are translated whether or
not video voice-over is enabled. Comment translation does not require a Yandex
token, a separate browser extension or your own server.

Translation uses external services: video information and, when required, original
audio are sent to Yandex; comment text is sent to Google. Personal user settings
and accounts are not included in the APK.

## Development

Build instructions and integration details are in [VOT_README.md](VOT_README.md)
(in Russian). Validation covered APK compilation, 32 audio/protocol/comment tests,
and Russian translation requests returning MP3 audio through the current API.
TV playback was not separately verified after the final branding change.

## Credits

Based on [SmartTube / yuliskov](https://github.com/yuliskov/SmartTube).
The translation integration builds on
[Voice Over Translation / ilyhalight](https://github.com/ilyhalight/voice-over-translation),
[vot.js](https://github.com/FOSWLY/vot.js) and [vot-cli / FOSWLY](https://github.com/FOSWLY/vot-cli).

This is an independent SmartTube modification. The upstream projects and their
authors are not responsible for this build's changes.
[MIT](LICENSE) · [Third-party credits and licenses](THIRD_PARTY_NOTICES.md).
