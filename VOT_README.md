# Разработка SmartTube VOT

Нативная интеграция Яндекс VOT в SmartTube 32.68 Stable (`32.68s`).
Версия приложения: `32.68-vot.1`, versionCode `2459`, пакет `org.smarttube.stable`.
Сверено с VOT `1.12.3` и vot.js `3.2.0`; компонент Яндекса `26.10.0.1796`.

## Сборка

Нужны JDK 17, Android SDK Platform 34 и Build Tools 30.0.3,
как в настройках подмодуля SharedModules. Gradle 7.5 запускается через wrapper.

```sh
git clone --recurse-submodules https://github.com/mikhkz/SmartTube-Russian-VoiceOver-and-Comments.git
cd SmartTube-Russian-VoiceOver-and-Comments
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
bash gradlew --no-daemon --max-workers=2 :smarttubetv:assembleStvotDebug
bash gradlew --no-daemon --max-workers=2 :common:testStstableDebugUnitTest --tests 'com.liskovsoft.smartyoutubetv2.common.vot.*'
```

APK находятся в `smarttubetv/build/outputs/apk/stvot/debug/`.
`universal` включает несколько архитектур. Для 32-битного ARM используется
`armeabi-v7a`, для 64-битного ARM — `arm64-v8a`.

Для сборки со своим постоянным ключом создайте локальный `keystore.properties`:

```properties
storeFile=/absolute/path/to/private-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

После настройки ключа соберите публичный выпуск:

```sh
bash gradlew --no-daemon --max-workers=2 :smarttubetv:assembleStvotRelease
```

Готовые APK: `smarttubetv/build/outputs/apk/stvot/release/`.
В публичный выпуск входят release-APK с отключённой отладкой.

Файл и ключ исключены из Git. Один и тот же закрытый ключ нужен для совместимых
обновлений. При отсутствии файла Android использует локальную debug-подпись.
Публичные артефакты CI не заменяют выпуски с постоянной подписью.

## Устройство интеграции

- `common/.../vot/`: HTTP, подписи HMAC, protobuf, сессии и получение перевода.
- `VoiceTranslateController`: управление запросом и вторым аудиоплеером.
- `TranslationAudioPlayer` и `TranslationGainAudioProcessor`: воспроизведение,
  независимая громкость и усиление с ограничением пиков.
- `NativeSabrAudioDownloader`: отдельная загрузка исходной звуковой дорожки с начала
  ролика. При `AUDIO_REQUESTED` отправляются реальные аудиочанки Яндексу.
- `VotData`: пользовательские настройки, включая необязательный OAuth.
- `CommentTranslator`: отдельный нативный перевод комментариев.
- `smarttubetv/src/stvot/res/values/branding.xml`: имя приложения и канал обновлений VOT.

Пауза, буферизация, перемотка и скорость второго плеера следуют основному.
При остановке перевода восстанавливаются громкость оригинала и tunneled playback.
Загрузка аудио ограничена 128 MiB и требует 512 MiB свободного места на устройстве;
временные файлы удаляются после завершения или отмены. Для прямых эфиров используется
резервный серверный сценарий.

Это Android-порт протокола, а не встроенное расширение браузера. DOM-интерфейс,
SolidJS, адаптеры других сайтов и браузерные настройки VOT в APK не включены.

## Выпуски

Собирать нужно flavor `stvot`, а не `stbeta` или `ststable`.
Для выпуска `v32.68-vot.1` публикуются APK, `SHA256SUMS` и `smarttube-vot.json`.
Содержимое манифеста находится в `distribution/smarttube-vot.json`.

Встроенный обновлятор читает только манифест VOT из последнего GitHub Release.
Номер versionCode должен расти при каждом выпуске. Ссылки манифеста указывают
на APK конкретного тега; подпись совместимых обновлений должна оставаться прежней.

Проверка актуального API: производственные Java-классы получили сессию,
статус 1 и MP3-аудио для обычного и живого режима русского перевода.
Подтверждены 32 модульных теста. Финальное воспроизведение этой версии на ТВ
не проверялось.
