# Как устроен geai

Документ описывает внутреннее устройство плагина: движки, промпты, модели, память
(три уровня), сжатие контекста и поток одного хода. Ссылки вида `File.kt:NN` указывают на
исходник.

---

## 1. Два движка

geai выполняет ход одним из двух движков — выбор по флагу `useClaudeCodeEngine`
(`GeaiAgentService.kt:89`).

| Движок | Класс | Бэкенд | Когда |
|---|---|---|---|
| **Native** | `AgentLoop` | свой HTTP-клиент к LLM по API-ключу | по умолчанию |
| **Claude Code** | `ClaudeCodeEngine` | локальный CLI `claude` по подписке | `useClaudeCodeEngine = true` |

Оба запускаются на фоновой отменяемой задаче (`GeaiAgentService.submit`, `:85`), в любой
момент времени активен ровно один ход. События обоих движков (`AgentEvent`) рисуются в UI и
сохраняют сессию после каждого результата инструмента (`savingListener`, `:75`).

---

## 2. Промпты

Системный промпт собирается в `SystemPrompt.kt`:

- **`SystemPrompt.build(project)`** (`:9`) = фиксированная **доктрина** (`BASE`) + живой
  **снимок проекта**. Используется нативным движком.
- **`SystemPrompt.doctrine()`** (`:13`) = только доктрина, без снимка. Передаётся Claude Code
  CLI через `--append-system-prompt-file` (`ClaudeCodeEngine.kt:90`) — снимок проекта CLI
  собирает сам.

### Доктрина (фиксированная часть, `SystemPrompt.kt:15-88`)
Задаёт поведение агента:
- **Миссия** — превратить расплывчатый баг-репорт в диагноз и (по запросу) фикс.
- **Operating principles** — сориентироваться (`project_overview` один раз), формировать
  гипотезы, трассировать поток данных source→sink, опираться на факты (`file:line`), править
  хирургически и идиоматично, верифицировать после правки.
- **Clarification** — при недоспецифицированной задаче задать 1-3 вопроса и остановиться.
- **Tools** — read-only можно свободно; mutating могут требовать апрува.
- **Method** — reproduce → localize → root cause → fix → audit.
- **Knowledge axes** — ПЕРЕД поиском звать `kb_lookup`; durable-факты писать через `kb_record`.
- **Self-modification** — через `self_info`/`self_patch`/`run_command` дописывать себе тулзы.
- **Output** — строгий регистр: коротко, по делу, на языке пользователя, без воды.

### Снимок проекта (`ProjectContextGatherer.kt:13`)
Дешёвый, считается в `ReadAction`. Содержит: имя проекта, base path, число модулей, авто-детект
системы сборки (Gradle/Maven/Node/unknown), до 25 топ-левел директорий (с фильтром
`.git/.idea/.gradle/build/out/target/node_modules/.geai`).

### Скиллы (быстрые промпты)
`GeaiSkills` — преднастроенные промпты-кнопки в UI (`GeaiWebPanel.skillsJson`, `:143`), каждый
просто подставляет готовый текст задачи.

---

## 3. Модели

Провайдеры — `LlmProvider.kt`:

- **ANTHROPIC** — нативный Claude Messages API (`/v1/messages`). Default `claude-sonnet-4-6`;
  предлагаются также `claude-opus-4-8`, `claude-haiku-4-5-20251001`.
- **OPENAI_COMPATIBLE** — любой сервер с диалектом OpenAI Chat Completions: DeepSeek, Qwen /
  DashScope, OpenRouter, локальные Ollama/LM Studio/vLLM. Default `deepseek-chat`.

Резолв клиента — `LlmClientFactory.create()` (`:13`): берёт провайдера, ключ из `GeaiSecrets`,
base URL, и строит `AnthropicClient` или `OpenAiCompatibleClient`. Конкретная модель —
`effectiveModel()` (`GeaiSettings.kt:48`): явно заданная или дефолт провайдера.

Claude Code engine модель не выбирает — её определяет подписка/конфиг CLI.

**Ключи** хранятся не в настройках, а в OS-хранилище через `GeaiSecrets` →
`PasswordSafe` (`GeaiSecrets.kt`), раздельно по провайдеру.

---

## 4. Память — три уровня

### Уровень 1 — рабочий контекст хода (RAM)
`AgentSession.messages` (`AgentSession.kt:14`) — список `ChatMessage` текущей сессии: user,
assistant, tool_result-блоки. Это то, что уходит модели каждый ход. Плюс `totalUsage`
(токены) и `claudeSessionId` (для resume у Claude Code).

### Уровень 2 — персист сессий (диск, JSON)
`GeaiSessionStore` (`GeaiSessionStore.kt`) пишет каждую сессию в
**`<project>/.geai/sessions/<id>.json`** (фолбэк — системный каталог IDE по `locationHash`,
если нет base path). Сохранение — best-effort (ошибки только логируются):
- `save` после каждого tool-результата / Done / Error / Cancelled (`GeaiAgentService.kt:78`);
- `loadMostRecent()` (`:53`) восстанавливает последнюю сессию при открытии;
- `listMeta()` (`:66`) — список истории для UI;
- Claude Code дополнительно продолжает свой контекст через `--resume <claudeSessionId>`
  (`ClaudeCodeEngine.kt:92`), с одной повторной попыткой при протухшем id (`:52`).

→ Disconnect / рестарт IDE не теряет расследование.

### Уровень 3 — индекс знаний проекта (диск, XML, версионируемый)
`GeaiKnowledgeStore` (`GeaiKnowledgeStore.kt`) — долгоживущий индекс в
**`<project>/.geai/knowledge.xml`**. Четыре оси (`KnowledgeEntry.kt`, `Axis`):

| Ось | Смысл |
|---|---|
| **NAV** | символ → `file:line` (где живёт класс/метод/хендлер), чтобы не искать заново |
| **STYLE** | стиль и конвенции проекта |
| **TECH** | техфакты: стек, сборка, фреймворки, инварианты |
| **LESSON** | анти-паттерны / чего нельзя делать (агент трактует как жёсткое ограничение) |

Инструменты: `kb_lookup` (читать перед поиском — экономит контекст), `kb_record` (писать
durable-факт), `kb_forget`. Обновление — оптимистичный **CAS** по `version`
(`GeaiKnowledgeStore.put`, `:95`): при несовпадении `expected_version` возвращается
`Conflict`, чтобы параллельные агенты не затирали друг друга. Чтение дешёвое (без сканирования
файлов).

> Отличие L2 от L3: сессии — это *история диалога*; индекс знаний — *выжимка фактов о проекте*,
> переживающая отдельные сессии.

---

## 5. Сжатие контекста

`ContextCompressor.compress(messages, contextWindowTokens, outputReserveTokens)`
(`ContextCompressor.kt`). Вызывается каждый ход нативного движка (`AgentLoop.kt:60`).

- **Бюджет в символах** считается из окна модели: `(maxContextTokens − maxTokens) × 4 × 0.8`
  (≈4 символа/токен, 20% запас), не ниже `MIN_BUDGET = 20 000`.
- Пока укладываемся в бюджет — транскрипт идёт без изменений.
- **Проход 1** — усекаются самые объёмные старые **выводы инструментов** (TOOL) до головы в
  400 символов.
- **Проход 2** (если всё ещё перебор) — усекается старый **текст** assistant/user.
- **Всегда сохраняются дословно**: исходная задача (сообщение `index 0`) и последние
  `KEEP_RECENT = 6` ходов.

Детерминированно, без дополнительного раунда к LLM (не суммаризует — усекает).

Окно задаётся настройкой `maxContextTokens` (default 200 000, `GeaiSettings.kt:32`),
резерв на ответ — `maxTokens` (default 8192).

---

## 6. Поток одного хода (нативный движок)

`AgentLoop.run` (`AgentLoop.kt:32`):

1. Добавить сообщение пользователя в сессию, отдать событие в UI.
2. Создать LLM-клиент (`LlmClientFactory`), собрать системный промпт.
3. Цикл до `maxAgentIterations` (default 32) или отмены:
   1. `ContextCompressor.compress(...)` → собрать `ChatRequest` (model, system, messages,
      tools = `registry.specs()`, maxTokens).
   2. `client.chat(request, indicator)` → добавить ответ в сессию, начислить токены.
   3. Нет tool-вызовов → выдать финальный текст, `Done`, выход.
   4. Есть — выполнить каждый (`executeTool`, `:120`): неизвестный инструмент → ошибка;
      mutating без авто-апрува → `ApprovalPolicy.confirm`; иначе `tool.execute`. На КАЖДЫЙ
      `tool_use` обязательно кладётся `tool_result` (даже при отмене — иначе транскрипт
      невалиден для провайдера, `:85`).
4. Отмена/`LlmException`/прочее ловятся и превращаются в события `Cancelled`/`Error`.

Транспорт (`HttpTransport.kt`): блокирующий JDK-`HttpClient`, отмена кооперативная (поллинг
`indicator` каждые 150 мс), ретраи с backoff на 429/502/503/504 с учётом `Retry-After`.

Движок Claude Code (`ClaudeCodeEngine.kt`) вместо этого поднимает in-process MCP-сервер
(`McpToolServer`), отдаёт CLI его инструменты как `mcp__geai__*`, парсит `stream-json` и
переводит в те же `AgentEvent`.

---

## 7. Гейты безопасности (сводно)

- **`ApprovalPolicy.confirm`** — модальный апрув mutating-инструментов (write/edit/run/self),
  если выключен `autoApproveEditTools`. Работает в обоих движках.
- **Конфайнмент путей** — `write_file` не перезаписывает файлы вне проекта
  (`FsPaths.isInsideProject`), `run_command` исполняет только внутри корня проекта.
- **MCP-токен** — per-session bearer (`McpToolServer.authToken`); CLI получает его в конфиге,
  чужие локальные процессы к `/mcp` не пройдут (401).
- **Секреты** — только в OS keychain (`GeaiSecrets`), никогда в `geai.xml`.
- **`self_patch`** — запись строго внутри настроенного source-root (проверка `startsWith`).

---

## 8. Где что лежит на диске

```
<project>/.geai/
  sessions/<uuid>.json   # L2: история диалогов (gitignored)
  knowledge.xml          # L3: индекс знаний (NAV/STYLE/TECH/LESSON)
geai.xml (IDE config)    # настройки (без ключей)
OS keychain              # API-ключи (per-provider)
```
`.geai/` уже в `.gitignore`.
