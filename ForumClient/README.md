# ForumClient — десктоп-клиент форума JCore

Windows-приложение для бэкенда **JCore** (`D:\AI\AiDev\Proj1`): окно на Avalonia,
внутри которого работает WebView2 с HTML/CSS/JS-оболочкой. Данные с сервером
обмениваются по **сырому TCP-протоколу** (не HTTP).

---

## Содержание

1. [Технологии](#технологии)
2. [Быстрый старт](#быстрый-старт)
3. [Архитектура](#архитектура)
4. [Структура проекта](#структура-проекта)
5. [Запуск и жизненный цикл приложения](#запуск-и-жизненный-цикл)
6. [Перехватчик запросов WebResourceRequested](#перехватчик-запросов)
7. [Мост JS ↔ C# (Bridge)](#мост-js--c-bridge)
8. [Транспорт: TCP-протокол бэкенда (JCoreApiClient)](#транспорт-tcp-протокол)
9. [Медиа: просмотр и загрузка файлов](#медиа-просмотр-и-загрузка)
10. [Веб-оболочка: страницы и js/api.js](#веб-оболочка)
11. [Переменные окружения](#переменные-окружения)
12. [Диагностика и логирование](#диагностика-и-логирование)
13. [Самотесты](#самотесты)
14. [Подводные камни (важно!)](#подводные-камни)
15. [Как расширять](#как-расширять)

---

## Технологии

| Слой | Технология |
|---|---|
| Окно/шелл | Avalonia 11.3 (`Avalonia`, `Avalonia.Desktop`, `Avalonia.Themes.Fluent`) |
| Веб-движок | Microsoft WebView2 1.0.* (WinForms-хостинг внутри `NativeControlHost`) |
| Целевой фреймворк | `net10.0-windows` (+ `UseWindowsForms` для хостинга WebView2) |
| Оболочка | Ванильные HTML/CSS/JS в папке `wwwroot` (без сборщиков и фреймворков) |
| Транспорт | Сырой TCP `127.0.0.1:8082`, UTF-8 + бинарная обёртка |

Требования: Windows 10+ с установленным **WebView2 Runtime** (Evergreen),
.NET SDK 10.

---

## Быстрый старт

```powershell
# 1. Бэкенд (нужен JDK 21+: JAVA_HOME -> jdk-21/22, и запущенный PostgreSQL)
cd D:\AI\AiDev\Proj1\JCore
mvn compile exec:java          # слушает 127.0.0.1:8082

# 2. Фронтенд (другой терминал)
dotnet run --project D:\AI\AiDev\ForumClient
```

Сборка «на каждый день» без IDE:

```powershell
dotnet build -c Release D:\AI\AiDev\ForumClient
D:\AI\AiDev\ForumClient\bin\Release\net10.0-windows\ForumClient.exe
```

Проверка транспортного слоя без GUI:

```powershell
dotnet run --project D:\AI\AiDev\ForumClient -- --selftest   # код выхода 0 = всё ок
```

---

## Архитектура

```
┌─────────────────────────── Процесс ForumClient.exe ───────────────────────────┐
│                                                                               │
│  Avalonia Window (MainWindow)                                                 │
│    └─ Controls.WebHost : NativeControlHost                                    │
│         └─ WinForms WebView2                                                  │
│              │                                                                │
│              │  https://jcore.forum/*  ← ВСЁ идёт через перехватчик           │
│              ▼                                                                │
│      OnWebResourceRequested                                                   │
│       ├─ статика        → файлы из wwwroot (html/css/js)                      │
│       ├─ GET /media/{id} → вложение с бэка сырыми байтами (+Range)            │
│       └─ POST /upload/{postId}/{имя} → загрузка файла к посту                 │
│                                                                               │
│  JS страниц ──postMessage({reqId,action,args})──► Services.Bridge             │
│      ▲                                            │                           │
│      └────PostWebMessageAsJson(ответ)─────────────┤                           │
│                                                   ▼                           │
│                                     Services.JCoreApiClient                   │
│                                                │  TcpClient (новый сокет      │
│                                                │  на каждый запрос)           │
└────────────────────────────────────────────────┼──────────────────────────────┘
                                                 ▼
                                   JCore Java-сервер 127.0.0.1:8082
```

Ключевая идея: **страницы не знают про TCP**. Они вызывают функции моста или
просто ссылаются на `/media/...`; вся работа с протоколом — в C#-слое.

---

## Структура проекта

```
ForumClient/
├─ ForumClient.csproj        net10.0-windows; UseWindowsForms; wwwroot копируется в вывод
├─ app.manifest
├─ Program.cs                точка входа; режим --selftest
├─ App.axaml(.cs)            тема Fluent, стили
├─ MainWindow.axaml(.cs)     окно; запуск первой навигации; оверлей фатальных ошибок
├─ SelfTest.cs               автотесты протокола на мок-сервере (6 тестов)
├─ Controls/
│   └─ WebHost.cs            NativeControlHost + WebView2 + перехватчик запросов
├─ Services/
│   ├─ JCoreApiClient.cs     ЕДИНСТВЕННОЕ место, знающее TCP-протокол; все эндпоинты
│   ├─ Bridge.cs             диспетчер запросов JS → JCoreApiClient; кэш вложений
│   ├─ ApiResult.cs          разбор ответа бэка (Success/Data/Error)
│   ├─ AppSession.cs         текущая сессия (логин/пароль в памяти процесса)
│   └─ Log.cs                файловый лог %LOCALAPPDATA%\JCoreForumClient\client.log
└─ wwwroot/
    ├─ index.html            загрузчик: редирект по наличию сессии
    ├─ login.html / register.html
    ├─ feed.html             лента постов подписанных групп
    ├─ catalog.html          поиск групп + «мои»/«подписки»
    ├─ group.html            страница группы: инфо, подписка, посты с превью медиа
    ├─ create-group.html     создание/редактирование группы (?id=)
    ├─ create-post.html      создание (?groupId=) с мультизагрузкой файлов;
    │                        редактирование (?postId=)
    ├─ post.html             пост: галерея медиа, комментарии, прикрепление файлов
    ├─ css/style.css
    └─ js/api.js             единственный общий скрипт: мост + хелперы UI
```

---

## Запуск и жизненный цикл

**Program.Main**: аргумент `--selftest` уходит в тесты, иначе стандартный
`StartWithClassicDesktopLifetime`.

**MainWindow** подписывается на три триггера первой навигации (с защитой от
повтора `_initialNavigationStarted`):

1. `AttachedToVisualTree`
2. `Opened`
3. `DispatcherTimer.RunOnce(..., 1500 мс)` — страховочный таймер-фолбэк

> ⚠️ Историческая причина тройной страховки: событие `AttachedToVisualTree`
> у окна в связке с NativeControlHost не всегда отрабатывает ожидаемым образом.
> Рабочий вариант — `Opened`; таймер гарантирует навигацию даже если оба
> события не сработали.

Стартовая страница берётся из переменной окружения `JCORE_START_PATH`
(по умолчанию `/index.html`). Это удобно для отладки:
`$env:JCORE_START_PATH='/post.html?id=54'`.

Ошибки навигации показываются полноэкранным красным оверлеем (`ShowFatal`),
а не молча.

**WebHost.CreateNativeControlCore** создаёт WinForms `WebView2` (Dock.Fill,
белый фон), асинхронная `InitializeAsync`:

* профиль WebView2 → `%LOCALAPPDATA%\JCoreForumClient\WebView2`;
* `EnsureCoreWebView2Async(environment)`;
* отключение контекстного меню и зума;
* установка фильтра перехватчика (см. ниже);
* `_ready.TrySetResult()` — после этого разрешены навигации и postMessage
  (`NavigateAsync`/`PostMessageToJsAsync` ждут этот Task и исполняются
  через `Dispatcher.UIThread`).

---

## Перехватчик запросов

Фильтр один на весь домен:

```csharp
core.AddWebResourceRequestedFilter("https://jcore.forum/*", CoreWebView2WebResourceContext.All);
core.WebResourceRequested += OnWebResourceRequested;
```

Маршрутизация внутри обработчика:

| Путь | Действие |
|---|---|
| `/media/{attachmentId}` | скачать вложение через `Bridge.GetAttachmentMediaAsync` и отдать сырыми байтами; поддержан `Range: bytes=start-end` → ответ `206 Partial Content` + `Content-Range` |
| `/upload/{postId}/{fileName}` | только POST; тело запроса = сырой файл → `Bridge.UploadToPostAsync` |
| остальное | статика из `wwwroot`: защита от выхода за корень (`Path.GetFullPath`), `/` → `index.html`, MIME по расширению, иначе 404 JSON |

Асинхронные ветки используют классический паттерн `e.GetDeferral()` …
`deferral.Complete()`. Ответы собираются через
`environment.CreateWebResourceResponse(new MemoryStream(body), status, reason, headers)`.

Кроме `WebResourceRequested`, WebHost обрабатывает `NewWindowRequested`:
`target="_blank"` и `window.open` не создают новых окон — ссылка открывается
навигацией в текущем WebView (в новом окне перехватчика нет, была бы страница
ошибки). Поэтому для полноэкранного просмотра фото на странице поста
используется лайтбокс (`#media-viewer`, открытие по клику, закрытие по Esc/клику
мимо), а не переход по ссылке.

> ⚠️ **Почему НЕ `SetVirtualHostNameToFolderMapping`?** Изначально домен
> `jcore.forum` отображался на папку `wwwroot` этим API — и тогда
> `WebResourceRequested` для ресурсов домена **вообще не срабатывает**
> (проверено на практике: медиа молча не грузилось). Поэтому маппинг убран
> и статика раздаётся тем же перехватчиком. Если захочется вернуть маппинг —
> перехватчик перестанет видеть трафик.

---

## Мост JS ↔ C# (Bridge)

Формат сообщений:

```
JS → C#:   { "reqId": 7, "action": "group.posts", "args": {"groupId":11,"page":1,"size":10} }
C# → JS:   { "reqId": 7, "ok": true, "data": {...} }
           { "reqId": 7, "ok": false, "error": "ACCESS_DENIED: ..." }
```

* `reqId` генерирует JS, C# возвращает его же — так сопоставляются ответы
  при параллельных запросах.
* `Bridge.HandleAsync` разворачивает двойное кодирование (`UnwrapIfString`),
  диспатчит экшен на типизированный метод `JCoreApiClient`, логирует короткий
  результат.
* На стороне JS (`js/api.js`) промисы кладутся в Map по `reqId`; есть
  **таймаут 20 секунд** с понятной ошибкой.

Поддерживаемые экшены (24):

```
session.get
auth.login | auth.logout | auth.register
feed.get
group.get | group.posts | group.search | group.my | group.subscribed
group.create | group.update | group.delete | group.subscribe | group.unsubscribe
post.get | post.create | post.update | post.delete | post.uploadFiles
attachment.download | attachment.delete
comment.list | comment.create | comment.update | comment.delete
```

Медиа и загрузки файлов идут **мимо моста** — через `/media/` и `/upload/`
(см. соответствующий раздел): base64 через postMessage не масштабируется
на тяжёлые файлы.

### Кэш вложений

`Bridge` держит LRU-кэш скачанных вложений (6 записей). Причина: браузер
запрашивает `<video>` несколькими Range-запросами, и без кэша каждый такой
запрос означал бы повторное скачивание всего файла с бэка по TCP.

---

## Транспорт: TCP-протокол

Единственный класс, который знает про сокеты — `JCoreApiClient`. На каждый
запрос открывается новый `TcpClient` (`NoDelay=true`), таймаут обмена — 3 минуты.

### Формат запроса

```
Controller/action<endl>param1<endl>param2 ... <endl>login<security>password<BINARY>
[int32 BE len][bytes файла]...[int32 BE len][bytes файла][int32 0]
```

* `<endl>` — **литеральная строка из 6 символов**, не перевод строки.
  Пример: `GroupController/getGroupPostsAction<endl>11<endl>1<endl>10<endl>1234<security>1234<BINARY>`
* Защищённые роуты требуют последний параметр — блок авторизации
  `логин<security>пароль`.
* Файлы: маркер `<BINARY>`, затем на каждый файл `[int32 big-endian длина][байты]`,
  в конце терминатор `[00 00 00 00]`. Отправляется всегда (даже без файлов).
* Порядок параметров для постов с файлами строгий:
  `обычные параметры → имена файлов → блок авторизации`
  (`ProtectedWithFiles`), т.к. бэк парсит имена файлов между ними.

### Валидация (до отправки)

Параметры и имена файлов не должны содержать `<endl>` и `<BINARY>`;
подстрока `<security>` допустима **только в последнем параметре**. Нарушение —
`ArgumentException` ещё на клиенте, а не мусорный запрос на сервере.

### Ответ

Одна строка до закрытия сокета (EOF). Разбор в `ApiResult.Parse`:

| Ответ | Результат |
|---|---|
| не начинается с `{` (например `ACCESS_DENIED: ...`) | `Success=false`, текст ошибки |
| пусто | `Fail("Пустой ответ от сервера")` |
| JSON со `status:"ERROR"` | `Fail(message)` |
| прочий JSON | `Success=true`, `Data` = корень |

Любое сетевое исключение превращается в
`Fail("Нет связи с бэкендом host:port (...)" )` — страницы получают понятную
ошибку вместо краха.

### Сессия

`AppSession` хранит `UserId/Name/Surname/Login/Password/Role` в памяти
процесса и подставляет `SecurityParam` во все защищённые методы через
хелперы `Protected(...)` / `ProtectedWithFiles(...)`. `LoginAsync` заполняет
сессию из ответа `loginAction`; выход — локальный `SignOut`.

---

## Медиа: просмотр и загрузка

**Просмотр** (страница поста, превью в списках):
`<img>/<video>/<audio src="/media/{id}">` → перехватчик → бэк
(`downloadAttachmentAction`, base64) → декодирование в байты → ответ браузеру
с правильным Content-Type. Видео получает Range-перемотку; повторные запросы
того же вложения обслуживаются из кэша.

**Загрузка** (создание поста, «Прикрепить файлы»):
JS читает выбранные файлы как объекты `File` и шлёт их **как есть**:

```js
fetch('/upload/' + postId + '/' + encodeURIComponent(file.name),
      { method: 'POST', headers: {'Content-Type':'application/octet-stream'}, body: file })
```

C# прочитает поток и передаст файл на бэк (`uploadPostFilesAction`). Никаких
`FileReader`/base64. Файлы грузятся последовательно, прогресс отображается на
кнопке («Загрузка файлов 2/5…»); неудачные файлы не ломают пост — показывается
их список.

Превью в списках постов (`enhancePostCardsWithMedia`): для карточек с
`attachmentsCount > 0` догружается `post.get`, берутся до 4 фото/видео и
вставляется полоса миниатюр (фото — картинкой, видео — первым кадром с ▶),
клик ведёт на страницу поста. Работает в ленте и на странице группы.

---

## Веб-оболочка

### js/api.js — справочник

| Функция | Назначение |
|---|---|
| `api(action, args)` | вызов моста, промис с таймаутом 20 с |
| `ensureSession()` | `session.get`; при отсутствии — автологин из localStorage (`jfc_login`/`jfc_password`); иначе редирект на `login.html` |
| `rememberCredentials(l,p)` / `logout()` | сохранение пары / выход с очисткой |
| `renderNav(activePage,user)` | верхняя панель навигации |
| `renderPager(el,page,pages,onPage)` | пагинация |
| `showSpinner/showEmpty/showError` | состояния списков |
| `esc/fmtDate/truncate/qs/toast` | утилиты (escape HTML, формат даты, обрезка, query-параметр, всплывашки) |
| `postCardHtml/groupCardHtml` | шаблоны карточек |
| `enhancePostCardsWithMedia(el,posts)` | превью медиа в списках |
| `uploadFileToPost/uploadFilesSequentially` | загрузка файлов через `/upload/` |

Глобальные обработчики `error`/`unhandledrejection` показывают ошибки скриптов
тостами — «белый экран» без объяснений больше не случится.

### Страницы

| Страница | Что делает |
|---|---|
| `index.html` | спиннер → редирект: есть сессия → `feed.html`, нет → `login.html` |
| `login.html` | вход, сохранение кредов в localStorage |
| `register.html` | регистрация → автоматический вход → лента |
| `feed.html` | посты подписанных групп, пагинация, превью |
| `catalog.html` | поиск групп + вкладки «Мои»/«Подписки», удаление своей группы |
| `group.html` | карточка группы, подписка/отписка, список постов с превью, CRUD владельца |
| `create-group.html` | создание; редактирование при `?id=` |
| `create-post.html` | создание `?groupId=` (после создания поста — загрузка файлов); редактирование `?postId=` (файлы скрыты — бэк принимает их только отдельным вызовом) |
| `post.html` | просмотр поста, галерея `/media/` с лайтбоксом для фото, комментарии (CRUD, пагинация), прикрепление файлов автором |

---

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `JCORE_HOST` | `127.0.0.1` | адрес бэкенда |
| `JCORE_PORT` | `8082` | порт бэкенда |
| `JCORE_START_PATH` | `/index.html` | стартовая страница оболочки (удобно для отладки: `/post.html?id=54`) |

---

## Диагностика и логирование

Всё пишется в `%LOCALAPPDATA%\JCoreForumClient\client.log`:

* запуск и аргументы, создание среды/WebView2, установка перехватчика;
* каждая навигация и её результат;
* каждый запрос моста (JSON, длинные значения обрезаны) и ответ;
* каждое обслуживание `/media/…` (статус, MIME, размер, был ли Range)
  и `/upload/…`;
* ошибки с полным стеком.

Если что-то «не работает» — первым делом смотреть сюда: по логу видно,
на каком шаге оборвалась цепочка (страница → мост → TCP → бэк).

---

## Самотесты

```powershell
dotnet run --project D:\AI\AiDev\ForumClient -- --selftest
```

Поднимается встроенный мок-TCP-сервер, повторяющий разбор запроса Java-сервера
(`Server.java`), и прогоняются 6 проверок:

1. Текстовый запрос + UTF-8
2. Бинарная обёртка (2 файла)
3. Валидация запрещённых подстрок
4. Разбор ответов (OK/ERROR/ACCESS_DENIED)
5. Блок авторизации последним параметром
6. Мост: сообщение в виде строки (регрессия двойного JSON-кодирования)

Код выхода 0 — всё пройдено. Полезно запускать после любых правок
`Services/*`.

---

## Подводные камни

Собранные на практике грабли — не наступите повторно:

1. **`SetVirtualHostNameToFolderMapping` глушит `WebResourceRequested`.**
   Пока домен замаплен на папку, перехватчик не видит его трафик вообще.
   Поэтому статика, медиа и аплоады раздаются одним универсальным
   перехватчиком, а маппинг не используется.
2. **`Window.AttachedToVisualTree` может не сработать.** Первая навигация
   запускается по `Opened` + страховочному таймеру 1500 мс. Не полагайтесь
   только на одно событие.
3. **Двойное JSON-кодирование моста.** `postMessage` со *строкой*
   приходит в C# как строка-в-строке. JS шлёт объект напрямую, а C#
   дополнительно умеет разворачивать (`UnwrapIfString`).
4. **Base64 через мост — только для мелочей.** Тяжёлые файлы упираются
   в таймауты/память; медиа ходит сырыми байтами через `/media//upload`.
5. **Видео = много Range-запросов.** Без кэша вложений каждое перемещение
   ползунка — новое скачивание файла с бэка.
6. **Порядок параметров при файлах:** параметры → имена файлов → блок
   `логин<security>пароль` (последний!). Бэк разбирает именно так.
7. **Редактирование поста не принимает файлы** — у `updatePostAction` их
   просто нет; файлы прикрепляются со страницы поста (`uploadPostFilesAction`).
8. **PowerShell 5.1 портит UTF-8** при `Get-Content | Set-Content` для файлов
   без BOM (кириллица превращается в mojibake). Правьте файлы редактором/инструментами.
9. **Бэк грузит файл целиком в память** (до 200 МБ) и возвращает base64
   (×1.33 к размеру) — клиент тоже держит копию в кэше. Для очень тяжёлых
   медиа закладывайте запас RAM.
10. **`target="_blank"` в WebView2 не работает из коробки** — попытка открыть
    новое окно даёт страницу «Не удаётся открыть эту страницу» (в новом окне
    нет нашего перехватчика). Фото открываются лайтбоксом внутри страницы,
    а `NewWindowRequested` в WebHost на всякий случай навигирует ссылку
    в текущем окне.

---

## Как расширять

### Новый эндпоинт бэка

1. `Services/JCoreApiClient.cs` — метод вида
   `public Task<ApiResult> XAsync(...) => SendAsync("Controller", "xAction", Protected(args));`
2. `Services/Bridge.cs` → `DispatchAsync` — новый `case "x.do":`
3. Из JS: `const res = await api('x.do', { ... });`

### Новая страница

1. Положите `mypage.html` в `wwwroot` (статика раздаётся автоматически).
2. Подключите `<script src="js/api.js">`, в старте:
   `const s = await ensureSession(); if (!s) return; renderNav('mypage.html', s);`
3. Ссылку добавьте в массив `links` внутри `renderNav` (api.js).

### Отладка конкретного экрана

```powershell
$env:JCORE_START_PATH='/group.html?id=11'; dotnet run --project D:\AI\AiDev\ForumClient
```

и смотрите `client.log` — там виден весь путь каждого запроса.
