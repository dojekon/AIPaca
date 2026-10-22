# Архитектура AiPaca

Одномодульное Android-приложение (`:app`, Kotlin + Compose), запускающее `llama-server`
из llama.cpp отдельным процессом. DI — ручной контейнер `AppContainer` в `AiPacaApp`
(без Hilt/Koin). minSdk 29, target/compileSdk 36, только `arm64-v8a`.

## Карта пакетов

```
com.aipaca.android
├─ AiPacaApp / AppContainer      ручной DI: Room, DataStore, EngineManager,
│                                ProfileRepository, DownloadManager
├─ MainActivity / ui/AppRoot     ModalNavigationDrawer + NavHost
│   (Модели (+ поиск HF) · Сервер API · Бенчмарк · Настройки · О приложении
│    + история чатов; загрузки — dropdown в топбаре «Моделей»)
│
├─ data/db                       Room v5: models, downloads, download_parts, chats,
│                                messages, bench_results, profiles_cache, bookmarks
│                                (MIGRATION_1_2: messages.reasoning; 2_3: bookmarks;
│                                3_4: download_parts.downloadedBytes; 4_5: chats.mode,
│                                messages.toolCalls/toolCallId, models.toolsSupported)
├─ data/device                   DeviceInfo → DeviceDescriptor
│                                deviceKey = vendor-soc/ramGb (qcom-sm8650/12gb),
│                                htpVersion по таблице SoC (sm8650 → 75)
├─ data/hf                       HfApi: поиск GGUF (?filter=gguf&sort=downloads),
│                                model details (?blobs=true, sha256, gated),
│                                HF-токен из настроек; raw-файлы шаблона чата
│                                (tokenizer_config.json / chat_template.jinja),
│                                ToolSupport.detectTools() — эвристика поддержки
│                                инструментов по тегам/шаблону/маркерам
├─ data/download                 DownloadEngine: probe Range, нарезка на N частей,
│                                Range-GET в RandomAccessFile, fallback single-stream,
│                                sha256, SpeedEma
│                                DownloadManager: состояния queued/running/paused/
│                                completed/cancelled/error, resume по download_parts,
│                                проверка etag, WorkManager (unique work), Wi-Fi-only,
│                                прогресс через StateFlow
│                                DownloadService (FGS dataSync) + DownloadWorker
├─ data/profiles/remote          ProfileFetcher (OkHttp, If-None-Match/304) +
│                                ProfileRepository: index.json → все ссылки,
│                                ETag-кэш в profiles_cache, merge с вшитыми
│                                (вшитые имеют приоритет по id/пути)
├─ data/settings                 DataStore: тема, язык, HF-токен, соединения,
│                                wifi-only, ускоритель (CPU/GPU/NPU, ключ
│                                `accelerator`; старые `gpu/npuEnabled` читаются
│                                для миграции), URL репозитория профилей,
│                                порт/API-ключ/LAN
├─ engine                        EngineManager: выбор порта, spawn
│                                nativeLibraryDir/libllama-server.so, аргументы из
│                                профиля, env-allowlist, LD_LIBRARY_PATH=
│                                nativeLibraryDir:/vendor/lib64:/system/lib64,
│                                ADSP_LIBRARY_PATH, polling /health (500 мс, до 180 с),
│                                кольцо логов (500 строк), exit-watcher, unload/kill
│                                EngineState: Unloaded/Loading/Ready/Error
│                                EngineService: FGS specialUse + уведомление «Остановить»
│                                Accelerator (CPU/GPU/NPU, storageKey, profileBackend),
│                                LaunchTarget (nGpuLayers/env/--device по выбранному
│                                ускорителю), NpuSupport.stage() — FastRPC-либы из
│                                /vendor в filesDir/npu-libs
├─ engine/chat                   ChatClient: POST /v1/chat/completions (stream) через
│                                okhttp, ручной SSE-парсер (content/reasoning/timings),
│                                stop через call.cancel(); ChatApi — модели запроса,
│                                tools-поле (ToolDef) и tool_call_id/tool_calls;
│                                ChatEvent.ToolCallDelta + ToolCallAssembler, ChatStreamer
├─ engine/agent                  AgentLoop: один запрос на шаг, последовательные
│                                tool-calls, maxIterations; AgentSystemPrompt (pi-style);
│                                ContextBudget/HistoryTrim/Compaction (бюджет, обрезка,
│                                авто-суммаризация); ModelToolProbe (/props truth-check);
│                                AgentMessages/AgentMessageCodec/AgentReducers
│                                tools/ — Sandbox (filesDir/agent) и инструменты
│                                list/find/grep/read/write/edit/fetch, AgentToolRegistry
├─ engine/profiles               ProfileCatalog/Loader (assets/profiles или JSON),
│                                ProfileParser, ProfileMatcher (accelerator-фильтр,
│                                backend-ранжирование, тиры device/same-soc/generic),
│                                ProfilePolicy (фильтр hexagon, CPU-откат),
│                                ServerArgBuilder (whitelist флагов и env)
└─ ui                            AppRoot/drawer, экраны: models (SAF-импорт, Load/
                                 Unload, лог, поиск HF подстраницей), chat (стриминг,
                                 параметры, системный промпт; в режиме агента —
                                 карточки tool-calls, счётчик шагов, divider
                                 компакции), server
                                 (порт/ключ/LAN/QR/тест /v1/models), DownloadsMenu —
                                 dropdown загрузок на экране «Модели» (pause/resume/
                                 cancel/удаление записей), settings, about;
                                 переключатель Chat | Agent в drawer фильтрует
                                 историю (ChatDao.observeForMode), Tools-плашки в
                                 поиске/деталях/моделях;
                                 theme (M3 light/dark/dynamic), RU/EN
```

## Поток «от модели до чата»

1. **Модель.** GGUF появляется одним из двух путей: импорт через SAF (копирование в
   `filesDir/models`, запись в `models`) либо загрузка с HF (DownloadEngine пишет файл
   по частям, DownloadManager ведёт таблицу `downloads`/`download_parts` и resume).
2. **Профиль.** `DeviceInfo.current()` даёт `deviceKey`; `ProfileMatcher.match(...,
   accelerator)` сопоставляет имя файла/repoId с `matchPatterns` профилей, извлекает
   квант, отбрасывает hexagon-профили при выборе не-NPU и ранжирует по backend
   (предпочтительный для ускорителя → cpu → прочие). Тир профиля: exact-device →
   same-soc (тот же SoC/вендор, другой объём RAM) → generic → default; внутри тира
   приоритет у совпадения кванта.
3. **Запуск.** `ModelsViewModel.loadModel()` стартует `EngineService`, прогоняет профиль
   через `LaunchTarget.apply(doc, accelerator)` (CPU: `nGpuLayers=0`; GPU/NPU: `-ngl 99`
   и `--device GPUOpenCL`/`--device HTP0`; env `GGML_HEXAGON_*` — только для NPU) и
   вызывает `EngineManager.load(path, doc, apiKey, lanEnabled, port)`.
   `ServerArgBuilder` собирает аргументы (`-m`, `--host/--port`, `-c`, `-t`, `-ngl`,
   `-b/-ub`, `-C` из `cpuMask`, `--jinja`, `--flash-attn on`, sampling, `extraArgs`
   c `--device`, `--api-key`) и env (`GGML_HEXAGON_*` из allowlist; при любом запуске —
   `LD_LIBRARY_PATH` со стейджнутыми FastRPC-библиотеками). Бинарник —
   `libllama-server.so` из `nativeLibraryDir`, cwd — `filesDir`. При сбое запуска
   на GPU/NPU `ModelsViewModel` автоматически повторяет загрузку с CPU-профилем
   (`models_gpu_fallback`/`models_npu_fallback`).
4. **Health.** Опрос `http://127.0.0.1:{port}/health` каждые 500 мс, таймаут 180 с;
   параллельно читается stdout/stderr в кольцевой лог, watcher ловит выход процесса.
   Успех → `EngineState.Ready(modelPath, port)` и `serverUrl = http://127.0.0.1:port`.
5. **Чат.** `ChatClient` шлёт OpenAI-совместимый потоковый запрос на
   `/v1/chat/completions`; SSE-события делятся на content и reasoning (reasoning
   показывается спойлером «Thinking»); из финального события берутся `timings` → t/s;
   stop — отмена корутины → `call.cancel()`. При выбранном NPU и ошибке запуска
   `ProfilePolicy.cpuFallback()` находит CPU-профиль (или берёт исходный), `LaunchTarget`
   применяет CPU-оверрайды и загрузка повторяется; в UI показывается `models_npu_fallback`.
6. **API-сервер.** llama-server сам предоставляет OpenAI-совместимый API на заданном
   порту (по умолчанию 8080, настраивается, применяется при следующей загрузке).
   LAN-режим — привязка к `0.0.0.0`; при включении автоматически генерируется API-ключ
   (флаг `--api-key`), в UI — URL local/LAN, QR, uptime и проверка `/v1/models`.

## Агентский режим (M7)

Сессии разделены по режиму: `chats.mode` (`'chat'` | `'agent'`), переключатель `Chat | Agent`
в drawer фильтрует историю через `ChatDao.observeForMode`, «Новый чат» создаёт сессию активного
режима; `ChatScreen` рендерит агентский UI, когда у чата `mode == 'agent'`.

- **Цикл (`AgentLoop`).** Один запрос к `/v1/chat/completions` на шаг. Если ответ несёт
  tool-calls — ассистентское сообщение сохраняется, инструменты выполняются последовательно,
  результаты пишутся как `role='tool'`, затем цикл повторяется (ограничение `maxIterations`,
  по умолчанию 25); текст без вызовов завершает ход. Гейтинг и URL движка — как в чате.
- **Стриминг tool-calls.** `SseParser` распознаёт `delta.tool_calls` (инкрементальные чанки
  `arguments` по `index`) и отдаёт `ChatEvent.ToolCallDelta`; `ToolCallAssembler` собирает
  финальный список `AssembledToolCall`.
- **Персистентность.** Ассистент: `content` (может быть пустым) + `toolCalls` = JSON
  `{id,name,argsJson}[]`. Результат инструмента: `role='tool'`, `toolCallId`, `content` = JSON
  `{"status":"ok|error","output":...,"meta":{...}}`. При отправке история конвертируется в
  OpenAI-форму (`AgentMessages.toApiMessages`), tool-строки ссылаются на `tool_calls` ассистента.
- **Инструменты.** Песочница `filesDir/agent` (`resolveInSandbox` отбрасывает абсолютные пути,
  `..` и симлинк-выходы) + `list`/`find`/`grep`/`read`/`write`/`edit`/`fetch`; `fetch` —
  только `http(s)`, таймаут 30 с, cap ~64 КБ, ответ персистится с обрезкой ~16 КБ.
  Интерфейс `AgentTool` + `AgentToolRegistry` задуман для подключения MCP-инструментов (M8).
  Одобрений нет (pi-style) — защита только песочницей и лимитами.
- **Промпт.** `AgentSystemPrompt` (pi-style, ~600–1000 токенов): роль, окружение, корень
  песочницы, «инструменты выполняются без подтверждения», по строке на инструмент, 4–6
  правил поведения; схемы инструментов не в промпте, а в `tools`-поле запроса.
- **Контекст.** `estimateTokens` = chars/4; `contextBudget(nCtx)` вычитает резерв под
  system+tools. При переполнении `trimHistory` сперва удаляет старые tool-результаты (маркер
  `[dropped from context]`), затем старые пары; если обрезки мало — `Compaction` вызывает
  не-стриминговый `ChatClient.complete()`, сохраняет сводку как `role='system'` и удаляет
  более старые сообщения (в UI — divider «Context compacted»). Ручной Compact — в меню агента.
- **Tools-плашка.** `ToolSupport.detectTools` — три сигнала (теги `tool-use`/`function-calling`,
  наличие файла шаблона, маркеры в тексте шаблона); плашка показывается в поиске/деталях/моделях
  (`toolsSupported`: null=неизвестно, 0/1). `ModelToolProbe` при первой загрузке читает
  `serverUrl/props` и корректирует сохранённое значение — истинная проверка шаблона.

## Нативные библиотеки

`engine.yml` / `scripts/build-engine.sh` собирают llama.cpp (CPU + OpenCL + Hexagon) и
кладут ~184 МБ `.so` в `app/src/main/jniLibs/arm64-v8a`. rpath не проставляется
(patchelf обычно отсутствует), поэтому приложение само выставляет `LD_LIBRARY_PATH`;
`/vendor/lib64` нужен для загрузки DSP-библиотек Qualcomm. HTP-skel — проприетарный,
бинарники не публикуются (см. README, todo.md «Риски»).
