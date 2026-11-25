# AGY Assistant — ИИ-помощник прямо в Minecraft (1.21.1 NeoForge)

Клиентский мод для **Minecraft 1.21.1 (NeoForge)**, который открывает внутриигровой чат с ИИ и умеет прикреплять контекст прямо из мира, инвентаря, JEI/EMI и экрана. Не требует установки на сервер — работает в одиночной игре и на любых серверах.

---

## Возможности

- **Захват цели в мире (`Alt + G`):**
  - Блоки (включая модовые) — ID, мод, свойства `BlockState`, теги, клиентский `BlockEntity` NBT, иконка блока.
  - Сущности — ID, HP, теги, иконка яйца призыва.
  - Игроки — **голова со скином игрока**, ник, предметы в руках, надетая броня, очки брони, режим игры и пинг.
  - Если под прицелом пусто — автоматически подхватывает **предмет из главной руки**.
- **Захват предметов из GUI (`Alt + G` при открытом инвентаре / сундуке / JEI / EMI):**
  - Наведи курсор на любой предмет и нажми `Alt + G` — прикрепит ID, название, tooltip, теги и Data Components 1.21.1.
- **Скриншоты экрана:** чекбокс `Attach screenshot` или кнопка `Shot` (сжимает в фоне в JPEG без фризов).
- **Кликабельные карточки в ответах:**
  - Если ИИ упоминает игровой ID (например `minecraft:wheat` или `create:mechanical_press`), под ответом появляются карточки с иконкой и tooltip:
    - **ЛКМ** — открыть рецепт в **JEI / EMI** (если их нет — скопировать ID).
    - **ПКМ** — открыть использования (**Uses**) в **JEI / EMI**.
    - **Shift + ЛКМ** — прикрепить этот предмет/блок в контекст нового вопроса.
- **Работа в фоне:** можно закрыть чат (`Esc`) пока агент думает — ответ продолжит генерироваться и будет ждать при следующем открытии (`G`).
- **3 провайдера на выбор:**
  - `Antigravity` (через локальный `agy-bridge.exe` + `agy` CLI или `GEMINI_API_KEY`, с опциональным read-only доступом к `config/`, `kubejs/`, `logs/latest.log`).
  - `OpenCode` (через локальный `agy-bridge.exe` + `opencode` CLI).
  - `OpenAI / Qwen / Ollama (Direct)` — **работает напрямую из `.jar`, без `agy-bridge.exe`** (идеально для друзей!).

---

## Управление (хоткеи)

Меняются в стандартных настройках управления Minecraft (категория **Antigravity Assistant**):

| Клавиша | Действие |
| :--- | :--- |
| `G` | Открыть окно чата |
| `Alt + G` | Захватить блок / сущность / игрока / предмет в руке или под курсором (инвентарь, сундук, JEI, EMI) и открыть чат |
| `Enter` | Отправить сообщение |
| `Esc` | Закрыть окно (генерация ответа продолжится в фоне) |

---

## Быстрая установка (без сборки)

### Вариант 1 — Одна команда в PowerShell (самый быстрый)
```powershell
irm https://raw.githubusercontent.com/pixiemays/agy-assistant-mc/main/install.ps1 | iex
```
Скрипт сам скачает последнюю версию мода (и `agy-bridge.exe`, если выбран полный режим) с [GitHub Releases](https://github.com/pixiemays/agy-assistant-mc/releases/latest) и зависимости с Modrinth.

### Вариант 2 — Готовый архив с GitHub Releases
1. Скачай со страницы [Releases](https://github.com/pixiemays/agy-assistant-mc/releases/latest):
   - **`agy-assistant-direct.zip`** (~215 КБ) — только мод для друзей (OpenRouter / Groq / Qwen / Ollama, без `.exe`).
   - **`agy-assistant-full-windows.zip`** (~65 МБ) — полный комплект с `agy-bridge.exe` (для Antigravity / OpenCode).
2. Распакуй и запусти двойным кликом **`install.bat`**:
   - Установщик сам найдёт все сборки в `ElyPrismLauncher`, `PrismLauncher`, `ModrinthApp`, `CurseForge`, `ATLauncher` и `.minecraft`.
   - Выбери номер нужной сборки (или `0`, чтобы указать путь вручную).
   - Установщик сам проверит `mods/` и при необходимости скачает с Modrinth зависимости (`Kotlin for Forge` и `oωo-lib` для NeoForge 1.21.1).
   - Выбери режим (`[1]` Direct API или `[2]` Antigravity/OpenCode с `agy-bridge.exe`).

---

## Бесплатные модели для друзей (режим Direct API, без `.exe`)

Настройки открываются в игре: **Mods -> AGY Assistant -> Config** или кнопка **`Config`** в правом верхнем углу чата. Выбери вверху `Provider: OpenAI / Qwen / Ollama (Direct, no .exe)` и укажи один из вариантов:

### 1. OpenRouter (бесплатные модели `:free`, включая Qwen3)
1. Зарегистрируйся на [openrouter.ai](https://openrouter.ai) -> **Keys** -> **Create Key**.
2. В `Config` мода укажи:
   - **Base URL:** `https://openrouter.ai/api/v1`
   - **API Key:** твой ключ `sk-or-v1-...`
   - **Model:** `qwen/qwen3-235b-a22b:free` (или любая другая бесплатная модель с суффиксом `:free`).

### 2. Groq (очень быстрый бесплатный тир)
1. Зарегистрируйся на [console.groq.com](https://console.groq.com) -> **API Keys** -> **Create API Key**.
2. В `Config` мода укажи:
   - **Base URL:** `https://api.groq.com/openai/v1`
   - **API Key:** твой ключ `gsk_...`
   - **Model:** `qwen/qwen3-32b` (или `llama-3.3-70b-versatile`).

### 3. Ollama (полностью локально и бесплатно, без интернета)
1. Установи [Ollama](https://ollama.com) и скачай модель: `ollama pull qwen3:8b`.
2. В `Config` мода укажи:
   - **Base URL:** `http://127.0.0.1:11434/v1`
   - **API Key:** *(оставить пустым)*
   - **Model:** `qwen3:8b`

### 4. Google AI Studio (бесплатный Gemini Flash)
- Работает в режиме **`Provider: Antigravity`** (через `agy-bridge.exe`): получи ключ на [aistudio.google.com](https://aistudio.google.com) и вставь в поле `Gemini API Key`.

---

## Режим для продвинутых: Antigravity / OpenCode (`agy-bridge.exe`)

1. Выбери в `Config` нужного провайдера:
   - `Provider: Antigravity (SDK / agy CLI)` — если `Gemini API Key` пустой, автоматически использует установленный в системе `agy.exe` с твоим аккаунтом Antigravity.
   - `Provider: OpenCode CLI (via Bridge)` — использует установленный `opencode` (`opencode run --format json --session ...`).
2. Опция **`Read-only access to modpack dir`** даёт агенту безопасный read-only доступ к папке твоей сборки (`config/`, `kubejs/`, `logs/latest.log`), чтобы он мог смотреть кастомные рецепты модпака (например в ATM10) и разбирать ошибки из логов.
3. При выходе из игры (и даже при краше) `agy-bridge.exe` автоматически завершается сам.

---

## Ручная установка (без установщика)

1. В `mods/` положи:
   - `agyassistant-0.1.0.jar`
   - `kotlinforforge-neoforge` (`5.x`, для 1.21.1)
   - `owo-lib-neoforge` (`0.12.x`, для 1.21.1)
2. *(Только если нужен Antigravity / OpenCode)* В `config/agyassistant/` положи `agy-bridge.exe`.

---

## Сборка из исходников

### Мод (`mod/`, требуется JDK 21)
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
cd mod
.\gradlew.bat build --no-daemon
# Результат: mod\build\libs\agyassistant-0.1.0.jar
```

### Мост (`bridge/`, требуется `uv`)
```powershell
cd bridge
uv sync --python 3.12 --extra dev --extra build
uv run pytest -v

# Сборка standalone agy-bridge.exe:
.\build_exe.ps1
# Результат: bridge\dist\agy-bridge.exe
```

---

## Частые проблемы

- **Индикатор `Offline` в режиме Antigravity/OpenCode:** проверь наличие `config/agyassistant/agy-bridge.exe` и лог `config/agyassistant/bridge.log`.
- **Индикатор `Offline` в режиме Direct API:** проверь, что в `Config` заполнены `OpenAI Base URL` и `OpenAI Model`.
- **При обновлении `agy-bridge.exe` пишет, что файл занят:** закрой Minecraft (или просто запусти `install.bat` — он сам остановит старый процесс `agy-bridge.exe`).
- **Скриншоты выдают ошибку в Direct API:** не все бесплатные модели поддерживают картинки (vision). Выключи галочку `Attach screenshot` или выбери vision-модель (например `qwen/qwen2.5-vl-72b-instruct:free`).
