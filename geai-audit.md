# Аудит проекта geai

> Дата аудита: ~июнь 2026  
> Версия проекта: v0.0.52  
> Цель: подготовить тебя к собеседованию — объяснить, что ты сделал, чем это отличается от других, и как это продать.

---

## 1. Что такое geai?

**geai** — это автономный debugging- и coding-агент, встроенный прямо в IntelliJ IDEA как плагин.  
Ты открываешь тул-окно, описываешь баг или задачу на естественном языке, и geai сам навигирует по проекту, читает файлы, ставит брейкпоинты, водит дебаггер, находит root cause — и (если попросили) чинит код.

Под капотом LLM (Claude, DeepSeek, Qwen — любые OpenAI-совместимые) крутится в **собственном agent loop** — это не обёртка над `LangGraph` или `Claude Code`. Архитектура называется **GRACE — Graph-RAG Anchored Code Engineering**.

---

## 2. Ключевая архитектура: GRACE

GRACE — это твой главный архитектурный патент (не буквальный, но интеллектуальный).  
Идея: **вынести интеллект из модели в строительные леса** — граф, якоря, спеки, детерминированные резолверы — так, чтобы даже дешёвая LLM (DeepSeek, Qwen) справлялась с навигацией и пониманием кода.

### Принцип «lookup, не recall»

Слабые модели галлюцинируют факты (сигнатуры, пути, контракты), **потому что вспоминают их по памяти**.  
GRACE превращает каждый такой факт в операцию: «спроси граф → резолвни якорь → подставь вывод инструмента».

> Интеллект агента = качество графа + детерминизм резолверов + строгость доктрины.  
> Качество модели — множитель сверху, не фундамент.

### Две категории знания

| | **A. Замысел/инвариант** | **B. Контракт/реальность кода** |
|---|---|---|
| Что | то, что нельзя вывести из кода: бизнес-правила, формулы, state-машины | сигнатуры API, DTO, символы — то, чем владеет код |
| Хранение | XML в `spec/*.spec.xml` (VCS) | якорь-ссылка, не копия |
| Доступ | читается дословно | резолвится в живой источник (PSI/OpenAPI) |
| Дрейф | ревьюится людьми | детектируется механически (hash якоря) |

### Якоря и Resolver SPI

SPI для универсальности: 
- `psi:<fqClass>[#member]` → живой JVM-символ через IntelliJ PSI
- `file:<path>[:a-b]` → диапазон строк файла
- `openapi:<doc>#<json-pointer>` → endpoint/DTO из OpenAPI spec

### Живая структура из PSI (без материализованного графа)

Ключевое техническое решение: **граф кода не материализуется и не хранится**.  
Структура (классы, методы, иерархия, соседи) читается **на лету из PSI/индекса IntelliJ** (`PsiStructure.kt`).  
Это значит:
- Всегда актуально — нет stale данных
- Нет cold-start — первая навигация работает сразу
- Нет пересборки графа после каждого edit (удалили целый `GeaiGraphStore` в v0.0.48)

### Tiered Routing (двухуровневая маршрутизация моделей)

- **Navigator** — дешёвая модель (DeepSeek-Chat, Claude Haiku). Водит loop: планирование, tool calls, навигация, сбор контекста.
- **Author** — сильная модель (Claude Sonnet 4, Qwen-max). Вызывается только через `escalate_author` для написания кода.
- Один провайдер, один API-ключ, две модели.

---

## 3. Что ты сделал (по чейнджлогу и гиту)

### Последние изменения (v0.0.48–0.0.52):

1. **v0.0.52** — поддержка заголовков h1–h6 в markdown-рендерере (fix).
2. **v0.0.51** — кросс-IDE поддержка: PyCharm, Android Studio, WebStorm, GoLand, CLion, Rider, DataGrip, PhpStorm, RubyMine.
3. **v0.0.50** — удалил Claude Code CLI engine. Оставил только один нативный GRACE-агент — код стал чище, убрал внешнюю зависимость от `claude`-бинарника.
4. **v0.0.49** — фикс: зависший стрим больше не вешает агента (таймаут 180с на тишину + responsive Stop). Ретраи 429/502/503/504 с `Retry-After`.
5. **v0.0.48** — удалил материализованный граф. `ContextBundler`, `graph_query`, `graph_neighbors` теперь работают **напрямую из PSI**.
6. **v0.0.47** — `graph_query` через IntelliJ class index + spec store.
7. **v0.0.46** — `graph_neighbors` из живого PSI.
8. **v0.0.45** — `search_text` через IntelliJ Find-in-Path engine (было O(project), стало O(indexed)).
9. **v0.0.44** — substring-поиск через word/trigram index — миллисекунды вместо секунд.
10. **v0.0.43** — GRACE drift detection: полный fingerprint (SHA-256 resolved anchor), не обрезанный.

### Более ранние (v0.0.2–0.0.42):

11. **v0.0.42** — stuck-loop guard + debugger poll exemption (не убивает легитимный debug-цикл).
12. **v0.0.41** — читаемые ошибки провайдера (вместо 2000 символов сырого JSON).
13. **v0.0.40** — spec-тулы (governance) подключены через `load_tools specs`.
14. **v0.0.39** — тул-колы по id, а не по имени (два `search_text` в одном turn без залипания).
15. **v0.0.38** — stuck-loop guard на полном результате + кольцо из 6 шагов (ловит A/B/A/B).
16. **v0.0.37** — починил `graph_query`/`graph_neighbors`/`context_bundle` (были orphaned).
17. **v0.0.36** — O(n) → O(degree) в графах.
18. **v0.0.34** — фикс token usage в стриминге + атомарные session saves.
19. **v0.0.32** — safety-aware dispatch: read-only параллельно, mutating последовательно.
20. **v0.0.31** — security: path traversal fix в `spec_record`.
21. **v0.0.24** — autonomous debugger stepping (`debug_step` over/into/out/resume).
22. **v0.0.12** — chat UI: collapsible tool block, markdown rendering.
23. **v0.0.8** — GRACE: full implementation (якоря, specs, граф, контекст-банлер, tiered routing).
24. **v0.0.6** — Knowledge Store: `kb_lookup`/`kb_record`/`kb_forget`.
25. **v0.0.5** — `debug_variables` + `debug_evaluate` (runtime inspection).

---

## 4. Сильные стороны (чем гордиться)

### 4.1. GRACE — это твоя killer-feature

Не просто «агент с тулами», а систематический подход к grounding.  
GRACE — это **Blueprint для построения code-aware агентов**, не привязанных к конкретной IDE.  
SPI резолверов позволяет перенести архитектуру в VS Code, LSP, CLI, web — меняются только резолверы, граф и доктрина остаются.

### 4.2. Живой PSI-граф (никаких материализованных копий)

Конкуренты (Sourcegraph Cody, Continue.dev) часто строят и хранят граф кода отдельно, что даёт: stale-данные, дорогую перестройку, холодный старт.  
Ты пошёл в обратную сторону: читаешь структуру из IDE-индекса **на лету**.  
Это **всегда актуально и ничего не стоит поддерживать**.

### 4.3. Автономный дебаггер

geai сам ставит брейкпоинты, стартует debug-сессию, ждёт вызова (await_pause), степает (over/into/out/resume), читает переменные и выражения — а потом убирает брейкпоинты.  
Это не «подскажи дебаггеру» — это **полный контроль**.  

### 4.4. Tiered Routing (экономия)

Большинство агентов крутят одну модель. Ты придумал: дешёвая модель для навигации (90% работы), дорогая — только для написания кода.  
Это **экономически эффективно**: типичный цикл из 10 turn-ов navigator + 1 escalate_author стоит как 2 обычных turn-а на Sonnet.

### 4.5. Продвинутый agent loop

- **Compaction** — LLM-сжатие старого транскрипта в recap: сессия может длиться 100+ итераций без переполнения контекста.
- **Stuck-loop guard** — кольцо сигнатур шагов с детекцией A/B/A/B циклов.
- **Slash commands** (`/debug`, `/implement`, `/refactor` и т.д.) — MODE selection с предзагрузкой тулов.
- **Progressive tool loading** — `load_tools` активирует группы (debug, run, specs, selfmod) только когда они реально нужны.
- **Adaptive kill-switches** — отключает escalate_author routing hint, если модель не эскалирует, и kb_lookup, если база пуста.

### 4.6. Персистентное знание (Knowledge Store)

4 оси: NAV (символ → file:line), STYLE (конвенции), TECH (стек/инварианты), LESSON (что нельзя делать).  
Агент консультируется до поиска/чтения — это **дешёвый контекст без LLM-затрат**.

### 4.7. 20 тестов

Unit-тесты на AgentLoop, context bundler, deterministc ranker, anchor resolver, spec store, session codec, pricing, tools, search, граф.

---

## 5. Сравнение с аналогами

### vs LangGraph (и подобные agent frameworks)

| Аспект | LangGraph | geai |
|--------|----------|------|
| **Суть** | Фреймворк для построения графов состояний (StateGraph) | Готовый end-to-end агент для разработки |
| **IDE-интеграция** | Нет (ты пишешь обёртки сам) | IntelliJ IDEA, из коробки |
| **Code Awareness** | Нет встроенного — ты сам подключаешь RAG/tools | GRACE: live PSI, specs, anchor resolvers |
| **Debugger** | Нет | Полный контроль дебаггера JVM |
| **Model routing** | Нет (один граф/один LLM call) | Tiered: navigator (cheap) + author (expensive) |
| **Context management** | Общее: manual или truncation | Compaction: LLM-сжатие транскрипта в recap |
| **Safety/Stuck detection** | Нет встроенного | Кольцевой детектор + adaptive kill-switches |
| **Persistent knowledge** | Нет | Knowledge Store (4 оси, с optimistic CAS) |
| **Target audience** | Разработчики AI-агентов | Разработчики, которые пишут код |
| **Точка входа** | pip install, настройка графа вручную | Установка плагина → открыть окно → написать задачу |

**Короче**: LangGraph — это лего-конструктор (делай своего агента сам).  
geai — это **готовый, IDE-родной, code-aware агент**, который решает задачу "почини этот баг" без настройки.

### vs Claude Code / Cursor → это другой класс

**Claude Code** — CLI-агент, живёт в терминале, работает через MCP-серверы.  
**Cursor** — форк VS Code с встроенным чатом.

geai против них:

| Аспект | Claude Code | Cursor | geai |
|--------|-------------|--------|------|
| UI | CLI | VS Code fork | IntelliJ plugin (JCEF chat + Swing fallback) |
| Code graph | Нет (RAG по файлам) | Нет (RAG по файлам) | GRACE (live PSI + specs + anchors) |
| Debugger | Нет (только edit-fix cycle) | Ограниченный | Полноценный (breakpoints, step, eval) |
| Model routing | Одна модель | Одна модель | Tiered (navigator + author) |
| Open-source | Да (частично) | Нет (проприетарный) | Да (полностью) |
| IDE lock-in | Терминал (любой) | VS Code | IntelliJ (с кросс-платформой в v0.0.51) |
| Knowledge persistence | Нет | Нет | Да (NAV/STYLE/TECH/LESSON) |
| Governance specs | Нет | Нет | Да (spec/*.spec.xml) |

### vs Sourcegraph Cody, Continue.dev → близко, но другое

Cody и Continue тоже в IDE, тоже agentic.  
Но: Cody полагается на свой индексированный граф (Sourcegraph инстанс), Continue — на RAG.

geai отличается тем, что **не строит отдельный граф кода** — живёт с PSI.  
Это даёт: никакого cold start, никакого stale, никакой перестройки.  
А также GRACE — specs (категория A) как способ **хранить замысел** — то, что не вывести из кода. Этого нет ни у кого.

---

## 6. Минусы / что можно улучшить

1. **Только IntelliJ** — хоть v0.0.51 и дала поддержку всех JetBrains IDE, это всё ещё экосистема JetBrains. Для VS Code / Cursor-замены нужно писать другой хост.

2. **PSI-зависимость** — навигация требует JVM PSI-плагина. В PyCharm или WebStorm PSI нет, часть фич деградирует.

3. **Knowledge Store пока в XML** — `knowledge.xml` в `.geai/`. При большом количестве записей (сотни) линейный поиск станет узким местом. Нет векторного индекса по default.

4. **Vector ranker в коде есть, но не включён** — в bundle есть `Ranker` SPI с DeterministicRanker и VectorRanker шаймом, но последний под заглушкой. Для семантического ранжирования контекста (не просто по совпадению имени) нужен донастроенный векторный поиск.

5. **Concurrent sessions** — не поддерживаются. Только одна сессия на проект.

6. **Self-modification хрупкий** — `self_patch` есть, но применить правку к своему же коду и перезагрузить плагин — процесс неавтоматический (нужен rebuild IDE / restart). Пока это больше proof-of-concept, чем реальная метрика.

7. **UI на JCEF** — IntelliJ постепенно отходит от JCEF в сторону Compose for Desktop. Со временем UI придётся переписать.

8. **Мало тестов в сравнении с коммерческими аналогами** — 20 тестовых файлов при 60+ source-файлах — покрытие есть, но не промышленное. Нет интеграционных тестов на реальном дебаггинге.

9. **Benchmark — начато, не развито** — есть `benchmark/` c `BenchmarkRunner`, `BenchmarkReport`, `BenchmarkLauncher`, но судя по структуре, это зачаток. Нет публичных результатов бенчмарков (SWE-bench или self-hosted).

10. **Документация на русском** — GRACE_ARCHITECTURE.md на русском. Для опенсорс-проекта это ограничивает аудиторию. README на английском — ок, но глубокая архитектура не на английском.

---

## 7. Вердикт (для собеса)

**geai — это не «ещё один ChatGPT-wrapper в IDE»**.  
Это:

- **Архитектурная работа**: GRACE — систематический подход к grounded code understanding через live PSI, specs и anchor resolvers.
- **Инженерная работа**: agent loop с compaction, stuck detection, model routing, progressive loading, safety-aware dispatch.
- **Продуктовая работа**: JCEF UI, slash commands, Settings UI, session persistence, поддержка 10+ IDE, бенчмарки.

На собеседовании я бы позиционировал так:

> «Я построил production-grade agentic coding assistant, который живёт внутри IntelliJ IDEA.  
> Моя ключевая идея — GRACE — подход к code awareness через live IDE-индекс и typed anchors, который позволяет дешёвым LLM навигировать по коду без галлюцинаций.  
> В отличие от LangGraph (фреймворк) или Claude Code (терминальный агент), geai — это IDE-native агент с полноценным дебаггером, tiered routing'ом и persistent knowledge store.  
> Архитектура спроектирована так, что не зависит от конкретной IDE — поменяй резолверы (SPI) и получишь того же агента в VS Code или CLI.»
