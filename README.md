# AiPaca

Android-приложение для локального запуска GGUF-моделей через llama.cpp на устройствах
Snapdragon (arm64-v8a). Инференс полностью на устройстве: без облака и без аккаунтов.
Оптимизировано под Snapdragon 8 Gen 3 (SM8650 / Galaxy S24 Ultra), но работает на любом
arm64-устройстве через generic-профили.

Статус: **M0–M6 закрыты** (модель → движок → чат → HF-загрузки → сервер → профили/бенч → полировка).
328 юнит-тестов, lint и сборка зелёные. Основной E2E-чеклист пройден на S24U (поиск/докачка,
LAN-curl, чат, бенчмарк, закладки); актуальные детали и отклонения — в [todo.md](todo.md).

## Возможности

| Возможность | Статус |
|---|---|
| Импорт GGUF (SAF), список моделей, Load/Unload, хвост лога движка | работает |
| Локальный движок: `llama-server` отдельным процессом, health-check, FGS-уведомление с «Остановить» | работает |
| Чат: стриминг, стоп/перегенерация/копирование, t/s из timings, параметры, системный промпт, история чатов, спойлер «Thinking» для reasoning-моделей | работает |
| Профили запуска: выбор ускорителя CPU/GPU/NPU в настройках, 8 вшитых (SM8650 + generic, включая Hexagon NPU) и обновление из своего GitHub-репозитория (ETag-кэш, авто раз в 24ч по Wi-Fi) | работает |
| Поиск и скачивание с Hugging Face: 1–8 соединений, докачка по частям (включая kill приложения), sha256, Wi-Fi-only, WorkManager, закладки | работает |
| OpenAI-совместимый API: порт, API-ключ, localhost/LAN, QR, проверка `/v1/models` | работает |
| Запуск в чате на выбранном ускорителе: CPU / OpenCL GPU / Hexagon NPU (с авто-откатом GPU/NPU→CPU при сбое) | работает |
| Бенчмарк CPU/OpenCL/NPU из приложения (pp512/tg128 ×3, история, экспорт) | работает |
| RU/EN, светлая/тёмная/динамическая тема | работает |

## Требования

- Устройство `arm64-v8a`, Android 10+ (minSdk 29); оптимально — SM8650 (или совместимый
  Qualcomm Snapdragon для OpenCL/NPU-профилей).
- RAM — по размеру модели (в 12 ГБ SM8650 комфортно идут 3–4B в Q4).
- Для сборки: JDK 17, Android SDK 36, Docker (движок) — см. [docs/BUILDING.md](docs/BUILDING.md).

## Быстрый старт

```bash
./scripts/build-engine.sh        # движок llama.cpp в Docker → jniLibs (184 МБ)
./gradlew :app:assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Затем в приложении: «Модели» → «Импорт GGUF» → выбрать файл → ▶ (Load) → новый чат.

`jniLibs` обязательны для работающего APK и в git не хранятся — см. [docs/BUILDING.md](docs/BUILDING.md).

## Тесты и проверка

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

330 юнит-тестов (JVM, без устройства). Живые проверки на устройстве выполняются через adb
(поиск, докачка с sha256, LAN-curl, бенчмарк) — см. агентские заметки в [AGENTS.md](AGENTS.md).

## Замеры

S24U (SM8650), Qwen3-4B Q4_0, pp128/tg64 t/s:

| Бэкенд | pp128, t/s | tg64, t/s | Примечание |
|---|--:|--:|---|
| CPU (4 потока, `-C 0xF0`) | 70.9 | 16.6 | работает из приложения |
| OpenCL (Adreno 750) | 97.8 | 9.6 | замер вне приложения: в контексте приложения платформа OpenCL недоступна, GPU-выбор откатывается на CPU |
| Hexagon NPU (HTP0) | 649.4 | 16.2 | работает из приложения (FastRPC-библиотеки стейджатся автоматически) |

E2E-чат из приложения (Qwen3-4B Q4_0, ctx 4096, KV q8_0): CPU ≈ 15.0 t/s, NPU ≈ 13.4 t/s;
выигрыш NPU — prefill (pp ≈ 1183 против ≈ 71) и разгрузка CPU (RSS ≈ 0.4 против ≈ 2.8 ГБ).

Бенчмарк из приложения на 0.6B BF16 (прогретый телефон): pp512 13.33 ± 1.68, tg128 8.46 ± 0.05 t/s.

## Структура репозитория

```
app/                  Android-приложение (Kotlin, Compose, Room)
  src/main/jniLibs/   собранный движок (не в git)
scripts/              build-engine.sh, engine-version.txt
profiles-repo/        шаблон GitHub-репозитория профилей + README
docs/                 ARCHITECTURE.md, PROFILES.md, BUILDING.md
.github/workflows/    CI: android.yml (lint+test+assembleDebug), engine.yml (сборка движка)
todo.md               план M0–M6 со статусами и отклонениями
```

## Документация

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — карта модулей и поток от модели до чата
- [docs/PROFILES.md](docs/PROFILES.md) — формат профилей, матчер, публикация своего репозитория
- [docs/BUILDING.md](docs/BUILDING.md) — пререквизиты, сборка движка и приложения, нюансы LD_LIBRARY_PATH
- [AGENTS.md](AGENTS.md) — заметки для ИИ-агентов: команды, карта кода, конвенции и грабли

## Дисклеймер

- NPU/Hexagon — **experimental**: приложение само копирует FastRPC-библиотеки
  (`libcdsprpc`, `libOpenCL`, `libvmem` и др.) из вендорного раздела в свой `filesDir`
  и запускает движок с `--device HTP0`; на S24U (SM8650) это работает, в том числе из
  обычного приложения (`untrusted_app`). Проверено E2E в чате на S24U (Qwen3-4B Q4_0,
  ~12–13 t/s). Совместимость с другими устройствами не гарантируется; при сбое приложение
  автоматически откатывается на CPU-профиль. NPU-профили учитываются матчером, только
  когда в настройках выбран NPU.
- GPU (OpenCL) в приложении недоступен на S24U: в контексте `untrusted_app` драйвер
  не перечисляет платформы (`ggml_opencl: platform IDs not available`) — при выборе GPU
  приложение автоматически откатывается на CPU (см. `models_gpu_fallback`).
- HTP-skel и прочие бинарные артефакты Qualcomm проприетарны и **не публикуются**;
  в том числе поэтому `jniLibs` собираются локально и не коммитятся в git.
- Приложение не является продуктом Qualcomm; Snapdragon, Hexagon, Adreno — торговые
  марки Qualcomm Technologies, Inc.
