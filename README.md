# Транскрибатор — офлайн-расшифровка речи для Android

Приложение переводит русскую речь из аудио, видео или записи с микрофона в текст:
с пунктуацией, делением на предложения и разделением по спикерам.
Всё работает на устройстве, без интернета (разрешения INTERNET в манифесте нет).

## Модели (лежат в `app/src/main/assets/models`, ~253 МБ)

| Задача | Модель | Лицензия |
|---|---|---|
| Распознавание + пунктуация | GigaAM v3 RNN-T e2e (SberDevices), int8 — `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16` | MIT |
| Поиск речи (VAD) | Silero VAD | MIT |
| Сегментация спикеров | pyannote segmentation 3.0 | MIT |
| Эмбеддинги голоса | WeSpeaker ResNet34-LM (VoxCeleb) | Apache-2.0 |

Движок — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (`app/libs/*.aar`).

## Как устроена обработка

1. `AudioDecoder` — MediaExtractor/MediaCodec: звуковая дорожка любого поддерживаемого
   системой формата → моно 16 кГц PCM во временный файл (память не растёт с длиной записи).
   Попутно `AacEncoder` сохраняет компактную копию m4a для прослушивания.
2. `Pipeline.recognize` — Silero VAD находит речь, соседние фразы склеиваются в отрезки
   до 22 с (больше контекста — точнее пунктуация), каждый отрезок распознаёт GigaAM v3.
   Токены с метками времени собираются в слова (`TextAssembler`).
3. `Diarizer` — pyannote + WeSpeaker. Длинные записи делятся на куски по ~8 мин
   (разрезы в паузах), в каждом куске свои спикеры, затем их голосовые «центроиды»
   кластеризуются глобально, чтобы «Спикер 1» был одним человеком на всей записи.
4. `TextAssembler.assemble` — предложения по знакам препинания, спикер каждого слова по
   перекрытию с репликами; если голос меняется посреди предложения, оно делится.

Перед распознаванием `Leveler` выравнивает громкость (усиление тихой речи до +26 дБ, мягкий лимитер):
на тестовом диалоге с голосом, ослабленным на 30 дБ, ошибки GigaAM упали с 48% до 2%.
Шумоподавление (GTCRN, DPDFNet) проверено и **не используется**: GigaAM устойчив к шуму сам
(0% ошибок при SNR 5 дБ), а подавители ухудшали результат на фоне голосов (29% → 37–48%).

Параметры диаризации (`windowShiftRatio = 0.5`, порог 0.65, модель WeSpeaker вместо CAM++)
подобраны на тестовом русском диалоге: CAM++ давал 3–7 ложных спикеров, WeSpeaker — 0 ошибок.

## Интерфейс

Jetpack Compose, своя дизайн-система «графит + коралл» (`ui/Theme.kt`), шрифт Onest (OFL, встроен в
`res/font`), светлая и тёмная темы. Экспорт: Word (.docx, собирается без сторонних библиотек в
`export/DocxWriter.kt`), TXT, SRT — отправка через системное меню «Поделиться» или сохранение в файл.
Приложение не зависит от сервисов Google (работает на Huawei/Honor без GMS).

## Сборка

Требуется JDK 17 и Android SDK 34.

```
./gradlew assembleRelease        # APK: app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease          # AAB: app/build/outputs/bundle/release/app-release.aab
./gradlew testDebugUnitTest      # модульные тесты (сборка текста, ресемплер)
```

Подпись релиза берётся из `keystore.properties` (в git не попадает):

```
storeFile=release-key/transcrib-release.jks
storePassword=...
keyAlias=transcrib
keyPassword=...
```

**Сохраните `release-key/transcrib-release.jks` и `keystore.properties` в надёжном месте.**
Без этого ключа нельзя будет выпускать обновления приложения в RuStore.

## Публикация в RuStore

Готовый комплект (всё, кроме APK) — `rustore/Transcrib-RuStore-1.0.0.zip`, внутри `README.txt` с таблицей
«файл → поле консоли». Исходники комплекта — папка `rustore/Transcrib-RuStore-1.0.0/`:

```
python rustore/make_assets.py rustore/Transcrib-RuStore-1.0.0            # иконка, логотипы, промо
python rustore/make_assets.py rustore/Transcrib-RuStore-1.0.0 --screens  # скриншоты из rustore/screens_raw
python rustore/make_package.py [--email you@example.com]                 # проверка лимитов, политика, zip
```

Перед публикацией: укажите e-mail (`--email`), разместите `06_privacy_policy/privacy-policy.html`
по публичной ссылке. `applicationId` (`ru.transcrib.app`) после первой публикации менять нельзя.

## Ограничения

- Нужен телефон с arm64 или armeabi-v7a и 3+ ГБ ОЗУ (x86_64 — только в debug-сборке, для эмулятора); модели занимают ~0.5 ГБ в памяти.
- Скорость на современных телефонах — примерно 10–30 минут на час записи.
- Число спикеров всегда определяется автоматически и может ошибаться на похожих голосах;
  в приложении можно объединить спикеров и переназначить абзац.
