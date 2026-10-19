<h1 align="center">AiPaca</h1>

<p align="center">
  <strong>Приватный инференс LLM прямо на Android-устройстве.</strong><br>
  GGUF-модели на Snapdragon на базе <a href="https://github.com/ggml-org/llama.cpp">llama.cpp</a>.
</p>

<p align="center">
  <strong>Русский</strong> · <a href="README.md">English</a>
</p>

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.2.21"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"></a>
  <a href="https://github.com/ggml-org/llama.cpp"><img src="https://img.shields.io/badge/llama.cpp-local%20inference-111111" alt="llama.cpp"></a>
  <a href="app/src/test"><img src="https://img.shields.io/badge/tests-330%2B-brightgreen" alt="330+ тестов"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" alt="GPL-3.0-or-later"></a>
</p>

AiPaca — нативное Android-приложение для запуска GGUF-моделей полностью на устройстве. Оно
оборачивает `llama-server` из llama.cpp в foreground-сервис и добавляет управление моделями,
стриминговый чат, возобновляемые загрузки с Hugging Face, профили запуска, бенчмарки и
OpenAI-совместимый HTTP API. Инференс полностью локальный: без облачного бэкенда и без аккаунта.

Приложение оптимизировано под Snapdragon 8 Gen 3 (SM8650 / Galaxy S24 Ultra), но generic-профили
для `arm64-v8a` работают и на других устройствах.

> **Статус проекта:** этапы M0–M6 закрыты (импорт модели → движок → чат → загрузки с HF →
> API-сервер → профили/бенчмарки → полировка). 330+ JVM-юнит-тестов, lint и debug-сборка
> зелёные. Основной end-to-end сценарий проверен на Galaxy S24 Ultra.

## Возможности

| Область | Что умеет |
|---|---|
| **Модели** | Импорт GGUF через Android SAF, список моделей, загрузка/выгрузка, просмотр лога движка в реальном времени. |
| **Локальный движок** | `llama-server` работает отдельным процессом с health-check и foreground-сервисом, в уведомлении есть кнопка **Остановить**. |
| **Чат** | Стриминг ответов, остановка/перегенерация/копирование, токены в секунду из timings сервера, параметры сэмплинга, системный промпт, история чатов и сворачиваемый блок **Thinking** для reasoning-моделей. |
| **Ускорители** | Выбор CPU, OpenCL GPU и Hexagon NPU с профилями запуска под устройство и автоматическим откатом на CPU, если GPU/NPU не стартовали. |
| **Профили запуска** | Восемь встроенных профилей для SM8650 и generic arm64, а также удалённый репозиторий профилей с ETag-кэшем и автообновлением раз в 24 часа по Wi-Fi. |
| **Hugging Face** | Поиск GGUF, загрузка через 1–8 соединений, докачка по частям (включая kill приложения), проверка SHA-256, режим «только Wi-Fi», интеграция с WorkManager и закладки. |
| **API-сервер** | OpenAI-совместимый endpoint с настраиваемым портом и API-ключом, доступом с localhost/LAN, QR-кодом и проверкой `/v1/models`. |
| **Бенчмарки** | Запуски CPU/OpenCL/NPU из приложения (pp512/tg128 ×3), история и экспорт. |
| **Интерфейс** | Русский и английский языки, светлая/тёмная/динамическая темы. |

## Требования

| Компонент | Требование |
|---|---|
| Устройство | `arm64-v8a`, Android 10 (API 29) или новее |
| RAM | Зависит от размера модели; на устройстве Snapdragon с 12 ГБ комфортно идут модели 3–4B в Q4 |
| Сборка | JDK 17, Android SDK 36, Docker (только для движка), около 11 ГБ свободного места |

Поддерживается только `arm64-v8a`.

## Быстрый старт

```bash
# 1. Собрать нативный движок (нужен Docker; ~184 МБ в app/src/main/jniLibs)
./scripts/build-engine.sh

# 2. Собрать debug-APK
./gradlew :app:assembleDebug

# 3. Установить на подключённое arm64-устройство
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Затем в приложении:

1. Откройте **Модели** → **Импортировать GGUF** и выберите `.gguf`-файл.
2. Нажмите **Загрузить** рядом с моделью.
3. Начните новый чат или откройте **Сервер API**, чтобы поднять OpenAI-совместимый API на localhost или в LAN.

> **Нативные библиотеки не хранятся в git.** `app/src/main/jniLibs/` исключён, потому что это
> ~184 МБ артефактов сборки. APK соберётся и без них, но движок не запустится.
> См. [docs/BUILDING.md](docs/BUILDING.md).

Скрипт движка собирает текущий `HEAD` llama.cpp и записывает коммит в
`scripts/engine-version.txt`. Если нужны воспроизводимые сборки, зафиксируйте ревизию
самостоятельно.

## Производительность

S24 Ultra (SM8650), Qwen3-4B Q4_0, `pp128`/`tg64`, токенов в секунду:

| Бэкенд | pp128 | tg64 | Примечание |
|---|--:|--:|---|
| CPU (4 потока, `-C 0xF0`) | 70.9 | 16.6 | Работает из приложения |
| OpenCL (Adreno 750) | 97.8 | 9.6 | Замер вне приложения: платформа OpenCL недоступна процессу `untrusted_app`, поэтому выбор GPU откатывается на CPU |
| Hexagon NPU (HTP0) | 649.4 | 16.2 | Работает из приложения; FastRPC-библиотеки стейджатся автоматически |

End-to-end чат из приложения (Qwen3-4B Q4_0, контекст 4096, KV `q8_0`): CPU ≈ 15.0 t/s,
NPU ≈ 13.4 t/s. Преимущество NPU — prefill (`pp` ≈ 1183 против ≈ 71) и разгрузка CPU
(RSS ≈ 0.4 ГБ против ≈ 2.8 ГБ).

Бенчмарк из приложения на модели 0.6B BF16 (прогретый телефон): pp512 13.33 ± 1.68,
tg128 8.46 ± 0.05 t/s. Цифры зависят от устройства и температуры.

## Технологии

| Слой | Стек |
|---|---|
| UI | Kotlin 2.2, Jetpack Compose, Material 3, Navigation Compose |
| Данные | Room 2.7, DataStore, WorkManager |
| Сеть | OkHttp, kotlinx.serialization |
| Нативный слой | llama.cpp / ggml с бэкендами CPU, OpenCL и Hexagon, сборка через Snapdragon toolchain |
| Внедрение зависимостей | Ручной `AppContainer` (без Hilt/Koin) |

## Структура репозитория

```text
app/                     Android-приложение (Kotlin, Compose, Room)
  src/main/jniLibs/      собранные библиотеки llama.cpp (не в git)
  src/main/assets/       встроенные профили запуска
scripts/                 скрипт сборки движка и информация о версии
profiles-repo/           пример GitHub-репозитория с профилями
docs/                    архитектура, профили и документация по сборке
.github/workflows/       CI для Android и сборка движка
```

## Тесты и CI

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Юнит-тесты — чистые JVM-тесты, без эмулятора, Robolectric и устройства.

GitHub Actions запускает ту же проверку в [`.github/workflows/android.yml`](.github/workflows/android.yml)
и выкладывает debug-APK артефактом. [`engine.yml`](.github/workflows/engine.yml) собирает нативный
движок в Docker и отдельно выкладывает `app/src/main/jniLibs` артефактом.

Поскольку нативные библиотеки не хранятся в git, для работающего APK нужен артефакт движка
(или локальный запуск `./scripts/build-engine.sh`) в `app/src/main/jniLibs/arm64-v8a`.

## Документация

| Документ | Содержание |
|---|---|
| [Архитектура](docs/ARCHITECTURE.md) | Карта модулей и путь от модели до чата |
| [Профили](docs/PROFILES.md) | Формат профилей, матчер и публикация собственного репозитория |
| [Сборка](docs/BUILDING.md) | Пререквизиты, сборка движка и приложения, нюансы `LD_LIBRARY_PATH` |
| [AGENTS.md](AGENTS.md) | Конвенции репозитория и практические заметки для ИИ-агентов |

## Известные ограничения

- **NPU (Hexagon) — experimental.** Приложение стейджит FastRPC-библиотеки (`libcdsprpc`,
  `libOpenCL`, `libvmem` и другие) из вендорного раздела в свой `filesDir` и запускает движок с
  `--device HTP0`. На S24U (SM8650) это работает end-to-end, в том числе из обычного процесса
  `untrusted_app`. Совместимость с другими устройствами не гарантируется; при сбое приложение
  автоматически откатывается на CPU-профиль. NPU-профили учитываются матчером, только когда NPU
  выбран в настройках.
- **OpenCL недоступен внутри приложения на S24U.** Драйвер не перечисляет платформы в контексте
  `untrusted_app` (`ggml_opencl: platform IDs not available`), поэтому выбор GPU откатывается на
  CPU. Замеры OpenCL возможны только вне приложения.
- **Вендорные бинарники не распространяются.** HTP-skel Qualcomm и другие проприетарные
  артефакты не публикуются. Библиотеки движка собираются локально и исключены из git.
- **Торговые марки.** AiPaca не является продуктом Qualcomm. Snapdragon, Hexagon и Adreno —
  торговые марки Qualcomm Technologies, Inc.

## Участие в разработке

Bug reports и pull requests приветствуются. Для крупных изменений сначала откройте issue, чтобы
обсудить подход. Перед отправкой PR запустите полную проверку выше.

## Благодарности

- [llama.cpp и ggml](https://github.com/ggml-org/llama.cpp) — движок инференса
- [Snapdragon toolchain](https://github.com/snapdragon-toolchain) — окружение для нативной
  Android-сборки

## Лицензия

AiPaca — свободное ПО: его можно распространять и/или изменять на условиях
[GNU General Public License v3.0 или новее](LICENSE).

Copyright (C) 2026 The AiPaca Authors.

Программа распространяется в надежде, что будет полезной, но БЕЗ КАКИХ-ЛИБО ГАРАНТИЙ; даже без
подразумеваемой гарантии ТОВАРНОЙ ПРИГОДНОСТИ или ПРИГОДНОСТИ ДЛЯ КОНКРЕТНОЙ ЦЕЛИ. Подробности —
в [GNU GPL v3](LICENSE).
