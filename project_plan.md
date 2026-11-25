# План проекта: Antigravity In-Game Assistant (Minecraft 1.21.1 NeoForge)

## 1. Обзор и цель
Создать клиентский мод для **Minecraft 1.21.1 (NeoForge)** на языке **Kotlin**, который открывает внутриигровое окно чата с **Antigravity** и позволяет по хоткею прикреплять к вопросу контекст из игры:
- цель под прицелом (блок, сущность, их ID, состояния, теги);
- предмет под курсором в инвентаре / JEI / EMI (Data Components 1.21.1);
- скриншот текущего экрана;
- текстовый вопрос или подпись пользователя.

Мод сам поднимает и контролирует локальный мост на базе **Antigravity Python SDK**, чтобы пользователю не приходилось вручную запускать терминалы или серверы.

---

## 2. Архитектура решения

```text
+-------------------------------------------------------+
| Minecraft 1.21.1 (NeoForge, Kotlin, oωo-lib)          |
|                                                       |
|  [Hotkey] -> ContextCollector (HitResult/Slot/Screen) |
|                    |                                  |
|                    v                                  |
|  [oωo-lib UI Screen] <-> HttpBridgeClient (SSE/JSON)  |
|                    ^                                  |
|  [BridgeProcessManager] (ProcessBuilder + healthcheck)|
+--------------------|----------------------------------+
                     | localhost:PORT (HTTP / SSE)
                     v
+-------------------------------------------------------+
| agy-bridge (Python + google-antigravity SDK)          |
|                                                       |
|  FastAPI -> Agent (LocalAgentConfig)                  |
|    - Стриминг ответа в мод                            |
|    - Опциональный read-only доступ к папке сборки     |
|      (config/, kubejs/, logs/latest.log)              |
+-------------------------------------------------------+
```

### Почему выбрана такая схема
1. **Чистый клиент:** мод не требует установки на сервер, работает в одиночной игре и на любых серверах.
2. **Официальный Antigravity SDK:** `google-antigravity` работает на Python, поэтому тонкий локальный HTTP-мост позволяет использовать полноценного агента со стримингом и доступом к файлам сборки.
3. **Бесшовный UX:** `BridgeProcessManager` в моде автоматически запускает `bridge.exe` (или Python-скрипт в dev-режиме) при старте клиента и корректно завершает его при выходе из игры.

---

## 3. Стек технологий

### Клиентский мод (JVM)
- **Minecraft:** `1.21.1`
- **Mod Loader:** `NeoForge` (21.1.x)
- **Язык:** `Kotlin` (через `Kotlin for Forge` для NeoForge 1.21.1)
- **JDK:** `21`
- **UI и конфиг:** `oωo-lib` (`owo-lib` для NeoForge 1.21.1)
- **HTTP-клиент:** встроенный `java.net.http.HttpClient` (поддержка асинхронных запросов и потокового чтения SSE без лишних зависимостей)
- **Опциональные интеграции (compileOnly / soft-depend):** `JEI` / `EMI` (чтение предмета под курсором в панели рецептов)

### Локальный мост (`agy-bridge`)
- **Язык:** `Python 3.11+`
- **SDK:** `google-antigravity`
- **Web-фреймворк:** `FastAPI` + `uvicorn`
- **Упаковка для пользователя:** `PyInstaller` (сборка в единый `agy-bridge.exe`, чтобы игроку не требовался установленный Python)

---

## 4. Структура проекта

```text
mc-agy-assistant/
├── mod/                                 # Gradle-проект NeoForge 1.21.1 (Kotlin)
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── src/main/
│       ├── kotlin/com/example/agyassistant/
│       │   ├── AgyAssistantMod.kt       # Точка входа, регистрация ивентов и конфига
│       │   ├── config/
│       │   │   └── ModConfig.kt         # Конфиг на базе oωo-config (порт, промпт, путь)
│       │   ├── input/
│       │   │   └── KeyBindings.kt       # Хоткеи (открыть чат, быстрый захват цели/скрина)
│       │   ├── context/
│       │   │   ├── ContextModels.kt     # DTO контекста (TargetInfo, ItemInfo, Screenshot)
│       │   │   ├── WorldContextCollector.kt   # HitResult: блок / сущность / координаты
│       │   │   ├── GuiContextCollector.kt     # Слот под курсором (ваниль + JEI/EMI)
│       │   │   └── ScreenCapture.kt     # Захват кадра -> сжатие PNG/JPEG -> Base64
│       │   ├── network/
│       │   │   ├── BridgeProcessManager.kt    # Запуск/проверка/остановка bridge.exe
│       │   │   └── BridgeHttpClient.kt        # Отправка запроса и приём SSE-стрима
│       │   └── ui/
│       │       ├── AssistantScreen.kt   # Главное окно чата на oωo-lib
│       │       └── ContextChipComponent.kt    # Визуальные плашки прикреплённого контекста
│       └── resources/
│           ├── META-INF/neoforge.mods.toml
│           └── assets/agyassistant/
│               ├── lang/ru_ru.json
│               ├── lang/en_us.json
│               └── owo_ui/assistant_screen.xml
│
└── bridge/                              # Локальный сервис на Python
    ├── pyproject.toml
    ├── src/agy_bridge/
    │   ├── main.py                      # FastAPI (/health, /chat, /reset)
    │   ├── agent_service.py             # Обёртка над google.antigravity.Agent
    │   ├── schemas.py                   # Pydantic-схемы запроса/ответа
    │   └── prompt_builder.py            # Формирование системного и пользовательского промпта
    └── build_exe.ps1                    # Сборка agy-bridge.exe через PyInstaller
```

---

## 5. Детальный план этапов разработки

### Этап 0. Инициализация окружения и скелета
- [ ] Создать структуру репозитория (`mod/` и `bridge/`).
- [ ] Настроить Gradle-проект NeoForge `1.21.1` с `Kotlin for Forge` и `oωo-lib`.
- [ ] Проверить запуск пустого клиента через `./gradlew runClient`.
- [ ] Настроить Python-окружение в `bridge/`, установить `google-antigravity`, `fastapi`, `uvicorn` и проверить тестовый запуск `Agent`.

### Этап 1. Локальный мост `agy-bridge`
- [ ] Реализовать эндпоинт `GET /health` для проверки готовности сервиса.
- [ ] Реализовать эндпоинт `POST /chat` со стримингом ответа (Server-Sent Events / NDJSON):
  - Принимает: `message`, `target_data` (JSON), `item_data` (JSON), `screenshot_base64` (опционально), `game_dir` (опционально).
  - Стримит токены ответа обратно клиенту.
- [ ] Настроить `LocalAgentConfig`:
  - Системный промпт под Minecraft (понимание ID предметов, модов, крафтов, логов).
  - Режим `read-only` с доступом к папке сборки (если включено в настройках мода).
- [ ] Реализовать `POST /reset` для сброса истории диалога.

### Этап 2. Сбор игрового контекста (Kotlin)
- [ ] Зарегистрировать хоткеи через `RegisterKeyMappingsEvent`:
  - **Открыть чат** (например, `G` или `Alt + C`).
  - **Захватить контекст и открыть чат** (например, `Alt + G`): сразу берёт цель под прицелом (или слот под курсором в GUI) + при необходимости скриншот.
- [ ] Реализовать `WorldContextCollector`:
  - `BlockHitResult` -> ID блока, мод, `BlockState` свойства, данные `BlockEntity` (если есть на клиенте).
  - `EntityHitResult` -> тип сущности, кастомное имя, здоровье/основные теги.
- [ ] Реализовать `GuiContextCollector`:
  - Перехват хоткея внутри `ScreenEvent.KeyPressed.Pre` (работает даже при открытом сундуке/инвентаре).
  - Извлечение `ItemStack` под мышкой (`AbstractContainerScreen.getSlotUnderMouse()`).
  - Опциональная интеграция с `JEI` (`IRecipeManager` / `IBookmarkOverlay` / `IIngredientListOverlay`) для чтения предмета прямо из списка рецептов.
- [ ] Реализовать `ScreenCapture`:
  - Захват изображения экрана в `NativeImage`, уменьшение разрешения (например, до 1280×720) для экономии токенов и скорости, кодирование в Base64.

### Этап 3. UI на `oωo-lib` и сетевой клиент
- [ ] Сверстать `AssistantScreen` на `oωo-lib` (XML + Kotlin):
  - Верхняя панель: статус подключения к мосту (зелёный/красный индикатор), кнопка «Сбросить чат», кнопка «Настройки».
  - Панель контекста: «чипы» с тем, что сейчас прикреплено (например, `[Блок: productivebees:advanced_beehive]`, `[Скриншот]`), с кнопкой `×` для удаления.
  - Лента сообщений со скроллом и автопрокруткой вниз при стриминге.
  - Нижняя панель: многострочное/длинное поле ввода (`TextBoxComponent`) + чекбокс «Прикрепить скрин» + кнопка «Отправить» (и отправка по `Enter`).
- [ ] Реализовать `BridgeHttpClient` на `java.net.http.HttpClient`:
  - Асинхронный запрос без блокировки рендер-потока.
  - Обновление текста сообщения по мере прихода чанков через `Minecraft.getInstance().execute { ... }`.

### Этап 4. Автозапуск и лёгкая установка
- [ ] Реализовать `BridgeProcessManager`:
  - При старте клиента ищет `agy-bridge.exe` в `config/agyassistant/` (или запущенный вручную порт для dev-режима).
  - Если порт свободен — запускает процесс через `ProcessBuilder` в фоне (без всплывающего окна консоли на Windows).
  - Регистрирует `Runtime.getRuntime().addShutdownHook(...)` и событие выхода из игры для гарантированного завершения процесса.
- [ ] Собрать `agy-bridge.exe` через `PyInstaller` (`--onefile --noconsole`).
- [ ] Подготовить финальный пакет установки:
  - `agyassistant-1.21.1.jar` -> в `mods/`
  - `owo-lib` + `kotlinforforge` -> в `mods/`
  - `agy-bridge.exe` -> в `config/agyassistant/` (или автоматическая распаковка из ресурсов мода при первом запуске, если размер позволит).

### Этап 5. Тестирование и полировка
- [ ] Проверить работу в реальной модпаке (например, с `Productive Bees` и `JEI`).
- [ ] Проверить корректность закрытия `agy-bridge.exe` при штатном выходе и при краше игры.
- [ ] Добавить отображение ошибок в чате (если мост не отвечает, нет авторизации или таймаут).

---

## 6. Ключевые риски и способы их закрытия
| Риск | Решение |
| :--- | :--- |
| Фризы игры при захвате скриншота или HTTP-запросе | Кодирование в Base64 и HTTP-стриминг выполнять строго в фоновых потоках (`CompletableFuture`), UI обновлять через `Minecraft.execute`. |
| Ложные срабатывания антивируса на `PyInstaller` `.exe` | Альтернативно можно поддерживать режим запуска через установленный `python`/`uv` в системе, если `.exe` заблокирован. |
| Зависание процесса моста после закрытия игры | Использовать `ShutdownHook` + периодический ping от моста к родительскому PID (мост сам завершается, если процесс Minecraft умер). |
| Большой вес скриншотов замедляет ответ | Масштабировать кадр перед отправкой (макс. ширина 1280px) и сжимать в JPEG (качество ~80%). |
