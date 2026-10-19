# Профили запуска (формат v1)

Профиль описывает, с какими параметрами запускать `llama-server` для конкретной модели
и устройства. Профили не входят в Kotlin-код: приложение читает JSON-каталог из
`assets/profiles/` (вшитые) и умеет подмешивать каталог из GitHub (кэш ETag в Room).

## Структура каталога

```
profiles/
├─ index.json            каталог: schemaVersion, updatedAt, devices[], profiles[]
├─ devices/*.json        спецификации устройств
└─ profiles/*.json       профили запуска
```

Шаблон для публикации лежит в [`profiles-repo/`](../profiles-repo/), там же
`schema/v1.schema.json` — JSON-схема всех трёх типов документов (валидируйте ей).

### index.json

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-10-16",
  "devices": [
    { "key": "qcom-sm8650/12gb", "soc": "SM8650", "vendor": "qcom",
      "ramGb": 12, "file": "devices/sm8650-12gb.json" }
  ],
  "profiles": [
    { "id": "qwen3-4b/q4_0/sm8650-12gb", "family": "qwen3-4b",
      "device": "qcom-sm8650/12gb", "quants": ["Q4_0", "Q4_0_8_8", "Q4_0_4_4"],
      "file": "profiles/qwen3-4b-q4_0-sm8650.json" }
  ]
}
```

### Устройство (devices/\*.json)

`key`, `soc`, `vendor`, `ramGb`, опционально `htpVersion` (≥68), `cores`
(`total`, `big`, `prime`, `perf`) и `cpuMasks` — именованные маски для `-C`
(например `allBig: "0xFC"`, `upperBig: "0xF0"`, `prime: "0x80"`).

### Профиль (profiles/\*.json)

| Поле | Описание |
|---|---|
| `schemaVersion` | всегда `1` |
| `id`, `backend` | `backend`: `cpu` · `opencl` · `hexagon` |
| `model.family` | семейство модели |
| `model.matchPatterns[]` | regex (без учёта регистра) по `repoId/имя файла` |
| `model.quants[]` | допустимые кванты |
| `launch.nCtx` / `threads` | `-c` / `-t` |
| `launch.cpuMask` | маска больших ядер → `-C` (llama.cpp сам делает `sched_setaffinity`) |
| `launch.nGpuLayers` | offload слоёв → `-ngl` (99 = все); фактическое значение переопределяется `LaunchTarget` по выбранному ускорителю |
| `launch.flashAttn`, `kvTypeK/V` | `--flash-attn`, `-ctk`/`-ctv` |
| `launch.batchSize` / `ubatchSize` | `-b` / `-ub` |
| `launch.mmap` | `false` → `--no-mmap` |
| `launch.extraArgs[]` | доп. флаги; whitelist: только `--flag` и безопасные значения (прежние `--device`/`-dev` вычищаются и заменяются по ускорителю) |
| `launch.env` | только переменные `GGML_HEXAGON_*`, например `GGML_HEXAGON_POWER_MODE`; применяются на NPU (на CPU/GPU вырезаются) |
| `sampling.*` | `--temp`, `--top-p`, `--top-k`, `--min-p`, `--repeat-penalty` |
| `expected.tgTps` / `ppTps` | ожидаемые t/s |
| `minAppVersion`, `experimental` | версия приложения; экспериментальный профиль |

`ServerArgBuilder` дополнительно всегда добавляет `-m`, `--host`, `--port`, `--jinja`
и `--api-key` (если задан в настройках сервера). `extraArgs` санитизируются: флаг —
`--[a-z0-9-]+`, значение без `/` и `..`; всё постороннее отбрасывается.

## Как выбирается профиль (ProfileMatcher)

1. Из имени файла извлекается квант (самое длинное совпадение из списка: `Q4_0_8_8`,
   `Q4_0_4_4`, `Q4_K_M`, `Q4_0`, `IQ4_XS`, `Q8_0`, `F16` и т.д.).
2. Кандидаты — профили, чьи `matchPatterns` совпали со строкой `repoId/имя файла`.
   Hexagon-профили участвуют, только когда в настройках выбран ускоритель NPU;
   внутри каждого тира кандидаты ранжируются по backend: предпочтительный для
   выбранного ускорителя → `cpu` → остальные.
3. Тир профиля:

   `exact-device+quant` → `same-soc+quant` → `generic-device+quant` → `exact-device`
   → `same-soc` → `generic-device` → `default`

   где exact — `device` вида `qcom-sm8650/12gb` совпадает с `deviceKey` устройства
   (`DeviceInfo`: vendor-сок/ramGb); same-soc — `soc`+`vendor` устройства из каталога
   совпали с реальными (актуально, когда отличается объём RAM, например
   `qti-sm8650/11gb`); generic — `device` начинается с `generic`;
   `default` — `model.family == "default"`.

4. Если профиля нет — Load заблокирован сообщением «No profile found for this model».

Вшитые профили: Qwen3-4B Q4_0 (SM8650, OpenCL + NPU-вариант), Qwen3-4B Q4_K_M
(generic), Llama-3.2-3B Q4_0 (SM8650), Llama-3.2-3B IQ4_XS (generic),
Gemma-3-4B Q4_0 (SM8650), default (generic, CPU).

## Свой репозиторий профилей

1. Форкните [`profiles-repo/`](../profiles-repo/) или скопируйте структуру на любой
   HTTP-хостинг (CORS не нужен — обычный GET).
2. Держите изменения в ветке `main` (для GitHub-ссылок она используется по умолчанию).
3. В приложении: «Настройки» → «Источник профилей» → URL репозитория и «Обновить».
   `https://github.com/USER/REPO` автоматически превращается в
   `https://raw.githubusercontent.com/USER/REPO/main/`.
4. Приложение скачивает `index.json` и все указанные в нём файлы; при повторных
   обновлениях неизменившиеся файлы не перекачиваются (ETag → HTTP 304).
5. Конфликты: профиль из репозитория **игнорируется**, если такой же `id` или путь
   файла уже есть среди вшитых. Применённый каталог = вшитые + уникальные удалённые.

## NPU (hexagon) — experimental

- Профиль с `backend: "hexagon"` виден матчеру только когда в настройках выбран
  ускоритель NPU; иначе `ProfilePolicy` его отфильтровывает.
- Работает только с квантами `Q4_0*` и на устройствах с HTP (SM8650 → `htpVersion 75`,
  в билде есть `libggml-htp-v73/v75/v79/v81`). Проверено на S24U: Qwen3-4B Q4_0
  (~12–13 t/s в чате) и gemma-4-E2B Q4_0 (~20–24 t/s, prefill ~500–690 t/s).
- В приложении NPU работает: `NpuSupport.stage()` копирует FastRPC-библиотеки
  (`libcdsprpc`, `libOpenCL`, `libvmem`, `vendor.qti.hardware.dsp@1.0`) из вендорного
  раздела в `filesDir`, `LaunchTarget` добавляет `--device HTP0` (и
  `GGML_HEXAGON_DEVICES=HTP0`, если в профиле нет своего), без `--device` offload
  в этой ревизии llama.cpp не включается вообще. При ошибке загрузки приложение
  автоматически откатывается на CPU-профиль и сообщает об этом.
- Замеры S24U, Qwen3-4B Q4_0, pp128/tg64 t/s: **CPU 70.9/16.6 · OpenCL (Adreno 750)
  97.8/9.6 · NPU (HTP0) 649.4/16.2**. NPU работает и в бенчмарке, и в чате приложения
  (E2E проверено, ~12–13 t/s); OpenCL-цифры получены вне приложения — в контексте
  `untrusted_app` драйвер не перечисляет платформы, GPU-выбор откатывается на CPU.
- HTP-skel Qualcomm проприетарный — профили публиковать можно, бинарники движка нет.
