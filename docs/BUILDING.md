# Сборка и установка

## Пререквизиты

| Компонент | Версия / примечание |
|---|---|
| JDK | 17+ (собирается temurin 17) |
| Android SDK | platform 36, build-tools; `ANDROID_HOME` или `local.properties` |
| Docker | нужен только для сборки движка |
| Свободный диск | ~11 ГБ: `build/engine` ~1.5 ГБ + `jniLibs` 184 МБ + кэши Gradle |
| adb | для установки на устройство |

Приложение: `minSdk 29`, `compileSdk/targetSdk 36`, только `arm64-v8a`.

## 1. Движок (llama.cpp)

```bash
./scripts/build-engine.sh            # JOBS=6 по умолчанию: JOBS=4 ./scripts/build-engine.sh
```

Что делает скрипт:

1. Проверяет Docker и ≥8 ГБ свободного места.
2. Клонирует `ggml-org/llama.cpp` (`--depth 1`, **HEAD**; если `build/engine/llama.cpp`
   уже существует — использует как есть, без fetch/reset).
3. Тянет образ `ghcr.io/snapdragon-toolchain/arm64-android:v0.7` (NDK r29, Snapdragon
   toolchain) и собирает preset `arm64-android-snapdragon-release` (CPU + OpenCL + Hexagon).
4. Раскладывает результат в `app/src/main/jniLibs/arm64-v8a`: `libllama-server.so`,
   `libllama-bench.so`, все `lib*.so`, при необходимости `libc++_shared.so` и
   `libQnnHtpV75Skel.so` (проприетарный, только локально).
5. Проверяет ELF (`AArch64`; htp-библиотеки — `QUALCOMM DSP6`) и копирует в jniLibs.

Версии сборки пишутся в `scripts/engine-version.txt` (и `build/engine/version.txt`).
Последняя локальная сборка: образ `v0.7`, preset `arm64-android-snapdragon-release`,
llama.cpp **23f7aabc16c72f6f707dba227251c35d7bd1ed96** (2026-10-16) — это HEAD на момент
клонирования; скрипт коммит не пинит, для воспроизводимости фиксируйте его сами.
Повторная сборка не обновит исходники: удалите `build/engine/llama.cpp` или обновите
его вручную.

## 2. Приложение

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**jniLibs обязательны.** Каталог `app/src/main/jniLibs/arm64-v8a` (~184 МБ) в git не
коммитится из-за размера. Без него Gradle соберёт APK (и CI пройдёт), но в пакете не
будет `libllama-server.so` и загрузка модели не заработает. Перед сборкой APK выполните
шаг 1 или положите библиотеки из артефакта CI.

### CI

`.github/workflows/android.yml` (JDK 17, `setup-gradle`, `testDebugUnitTest` +
`lintDebug` + `assembleDebug`) **предполагает, что jniLibs уже на месте** —
workflow лишь предупреждает, если каталог пуст. Чтобы получить библиотеки:

1. запустите вручную workflow `Build engine` (`workflow_dispatch`);
2. скачайте артефакт `engine-jniLibs-arm64-v8a` и распакуйте в
   `app/src/main/jniLibs/arm64-v8a`.

Артефакты Actions доступны только внутри своего запуска, поэтому для полностью
автоматического CI добавьте шаг получения библиотек (release-ассет, кэш или внешнее
хранилище) перед сборкой APK.

## 3. Установка

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Далее в приложении: «Модели» → «Импорт GGUF» (SAF) → Load. Хвост лога движка — там же и
на экране «Сервер API».

## Нативные библиотеки в рантайме

- rpath в собранных `.so` не проставляется (patchelf обычно отсутствует), поэтому
  `EngineManager` при спавне задаёт
  `LD_LIBRARY_PATH = nativeLibraryDir:/vendor/lib64:/system/lib64` и
  `ADSP_LIBRARY_PATH = nativeLibraryDir`. `/vendor/lib64` нужен для загрузки
  Qualcomm DSP-библиотек; `ldd` на устройстве для этих `.so` неинформативен —
  проверяйте `readelf -d`.
- `useLegacyPackaging = true`, HTP-библиотеки (`libggml-htp-v73/v75/v79/v81`) внесены
  в `keepDebugSymbols` — они должны попадать в APK как есть.
- HTP-skel и прочие бинарники Qualcomm не публикуйте (см. README).

## Полезные команды

```bash
./gradlew :app:testDebugUnitTest          # unit-тесты
./gradlew :app:lintDebug                  # Android Lint
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep lib/arm64   # либы в APK
readelf -d app/src/main/jniLibs/arm64-v8a/libllama-server.so          # RUNPATH
```
