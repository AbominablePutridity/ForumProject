# ForumClient — десктоп-клиент форума JCore

Кроссплатформенное приложение для бэкенда **JCore** (`D:\AI\AiDev\ForumProject\ForumServer`):
окно на Avalonia, внутри которого работает веб-оболочка (Windows — WebView2,
Linux — WebKitGTK, macOS — WKWebView) с HTML/CSS/JS. Данные с сервером
обмениваются по **сырому TCP-протоколу** (не HTTP).

---

## Содержание

1. [Технологии](#технологии)
2. [Быстрый старт](#быстрый-старт)
3. [Кроссплатформенность](#кроссплатформенность)
4. [Архитектура](#архитектура)
5. [Структура проекта](#структура-проекта)
6. [Запуск и жизненный цикл приложения](#запуск-и-жизненный-цикл)
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
| Веб-движок | `WebView.Avalonia` (`WebView.Avalonia.Desktop`) — абстракция над системными движками |
| — Windows | WebView2 (Chromium/Edge Runtime) |
| — Linux | WebKitGTK |
| — macOS | WKWebView (системный) |
| Целевой фреймворк | `net10.0` (нативно кроссплатформенный, не `-windows`) |
| Оболочка | Ванильные HTML/CSS/JS в папке `wwwroot` (без сборщиков и фреймворков) |
| Транспорт | Сырой TCP `127.0.0.1:8082`, UTF-8 + бинарная обёртка |

`app.manifest` подключается только на Windows (условие `IsOSPlatform('Windows')`)
и на других ОС игнорируется. Windows-только пакетов и кода в проекте нет.

---

## Быстрый старт

```powershell
# 1. Бэкенд (нужен JDK 21+: JAVA_HOME -> jdk-21/22, и запущенный PostgreSQL)
cd D:\AI\AiDev\ForumProject\ForumServer\JCore
mvn compile exec:java          # слушает 127.0.0.1:8082

# 2. Фронтенд (другой терминал)
dotnet run --project D:\AI\AiDev\ForumProject\ForumClient
```

Сборка «на каждый день» без IDE:

```powershell
dotnet build -c Release D:\AI\AiDev\ForumProject\ForumClient
D:\AI\AiDev\ForumProject\ForumClient\bin\Release\net10.0\ForumClient.exe
```

Проверка транспортного слоя без GUI (работает на любой ОС):

```powershell
dotnet run --project D:\AI\AiDev\ForumProject\ForumClient -- --selftest   # код выхода 0 = всё ок
```

---

## Кроссплатформенность

Приложение собирается и работает на Windows, Linux и macOS. Весь код
(C# и JS) использует только кроссплатформенные API; платформенный выбор
движка делает `WebView.Avalonia` автоматически через `UsePlatformDetect()` +
`UseDesktopWebView()`.

| ОС | Движок | Системные зависимости |
|---|---|---|
| Windows | WebView2 (Edge Runtime) | WebView2 Runtime (обычно уже установлен с Edge) |
| Linux | WebKitGTK (через GTK3) | `libwebkit2gtk-4.0` / `webkit2gtk-4.1` (Debian/Ubuntu: `sudo apt install libwebkit2gtk-4.1-0`), GTK3, X11 или Wayland |
| macOS | WKWebView (системный) | нет доп. зависимостей (используется Xamarin.Mac/WKWebView) |

**Как движок выбирается:** `WebView.Avalonia.Desktop` связывает
`WebView.Avalonia.Windows` / `.Linux` / `.MacCatalyst`; на конкретной ОС
активируется только соответствующий провайдер (проверено: сборка под
`linux-x64` и `osx-x64` проходит, в выводе видны `WebkitGtkSharp.dll` /
`Xamarin.Mac.dll`).

**Мост JS ↔ C# кроссплатформенный** (api.js): на Windows —
`window.chrome.webview.postMessage`, на WebKit/WKWebView —
`window.webkit.messageHandlers.webview`, запасной —
`window.external.sendMessage`. Обратное направление C# → JS — через
глобальный колбэк `__dispatchMessageCallback` (он же + `chrome.webview` на Windows).

**Медиа и файлы** передаются base64-строками через мост
(`attachment.download` → base64 → Blob → objectURL; загрузка — `files: [{name, dataBase64}]`).
Это работает везде; для очень крупных файлов возможны лимиты моста.

### Сборка и публикация под каждую ОС

```powershell
# Windows
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained false -o publish/win

# Linux (x64)
dotnet publish -c Release -r linux-x64 --self-contained false -o publish/linux

# macOS (x64) — собирается на любой ОС, запуск только на маке
dotnet publish -c Release -r osx-x64 --self-contained false -o publish/osx
```

После публикации:
* **Windows**: `publish\win\ForumClient.exe`
* **Linux**: `cd publish/linux && dotnet ForumClient.dll` (или chmod +x `ForumClient`)
* **macOS**: `cd publish/osx && dotnet ForumClient.dll`

Проверка транспортного слоя без GUI работает на всех ОС:
`dotnet run -- --selftest`.

---

## Архитектура

```
┌──────────────────────────────── Дерево процесса ───────────────────────────────┐
│                                                                                │
│  Avalonia Window (MainWindow)                                                  │
│    └─ Controls.WebHost : UserControl <WebView>(WebView.Avalonia) →             │
│         Windows → WebView2,  Linux → WebKitGTK,  macOS → WKWebView             │
│              │                                                                │
│              │  Страницы из bundled-папки wwwroot, грузятся по file://         │
│              │  (никакого HTTP-перехватчика нет — вся связь через мост)        │
│              ▼                                                                │
│  JS страниц ──postMessage({reqId,action,args})──► Services.Bridge              │
│      ▲                                            │                           │
│      └────PostMessageToJsAsync(ответ, JSON)───────┤                           │
│                                                   ▼                           │
│                                     Services.JCoreApiClient                    │
│                                                │ TcpClient (новый сокет        │
│                                                │ на каждый запрос)             │
│                                                   ▼                           │
│                                    JCore Java-сервер 127.0.0.1:8082            │
│                                                                                │
│  Медиа: attachment.download → base64 → Blob → objectURL (в JS)                │
│  Загрузка: post.uploadFiles / post.create с {name, dataBase64}                 │
└────────────────────────────────────────────────────────────────────────────────┘
```

Ключевая идея: **страницы не знают про TCP**. Они вызывают функции моста
(`api('post.get', {...})`); вся работа с протоколом — в C#-слое.

---

## Структура проекта

```
ForumClient/
├─ ForumClient.csproj        net10.0 (кроссплатформенно); манифест только на Windows;
│                            RuntimeIdentifiers win-x64;linux-x64;osx-x64; wwwroot → вывод
├─ app.manifest               только Windows (подключается условно)
├─ Program.cs                точка входа; режим --selftest; UsePlatformDetect + UseDesktopWebView
├─ App.axaml(.cs)            тема Fluent; AvaloniaWebViewBuilder.Initialize
├─ MainWindow.axaml(.cs)     окно; запуск первой навигации; оверлей фатальных ошибок
├─ SelfTest.cs               автотесты протокола на мок-сервере (6 тестов)
├─ Controls/
│   └─ WebHost.cs            WebView.Avalonia (WebView2/WebKitGTK/WKWebView) + мост,
│                            file://-навигация по wwwroot
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
> у окна не всегда отрабатывает ожидаемым образом (надёжности ради используется
> `Opened`), а таймер гарантирует навигацию даже если оба события не сработали.

Стартовая страница берётся из переменной окружения `JCORE_START_PATH`
(по умолчанию `/index.html`). Это удобно для отладки:
`$env:JCORE_START_PATH='/post.html?id=54'`.

Ошибки навигации показываются полноэкранным красным оверлеем (`ShowFatal`),
а не молча.

**WebHost** создаёт `WebView` из `WebView.Avalonia` (белый фон; на Windows —
WebView2, на Linux — WebKitGTK, на macOS — WKWebView). Готовность движка
приходит событием `WebViewCreated`, затем:

* навигации и postMessage разблокированы (`_ready`), вызовы идут через
  `Dispatcher.UIThread` (`NavigateAsync`/`PostMessageToJsAsync` ждут Task);
* профиль/кэш веб-движка — в `%LOCALAPPDATA%\JCoreForumClient\WebView2`
  (на Linux/macOS это `~/.local/share/JCoreForumClient` и т.п.).

---

## Загрузка страниц (file://) и кроссплатформенные события

**В текущей (кроссплатформенной) реализации HTTP-перехватчика НЕТ.**
`WebHost` грузит страницы из bundled-папки `wwwroot` по `file://` и общается
с JS только через мост. Платформенный выбор движка делает `WebView.Avalonia`.

События WebView (общие для WebView2/WebKitGTK/WKWebView):

* `WebViewCreated` → `_ready` (после него разрешены навигации и postMessage;
  `NavigateAsync`/`PostMessageToJsAsync` ждут Task и исполняются через
  `Dispatcher.UIThread`);
* `NavigationCompleted` → лог результата;
* `WebViewNewWindowRequested` → `target="_blank"` / `window.open` не создают
  новых окон: ссылка открывается навигацией в текущем WebView
  (`UrlLoadingStrategy = OpenInWebView`). Для полноэкранного просмотра фото
  на странице поста используется лайтбокс (`#media-viewer`, Esc/клик мимо).

> ℹ️ **Почему так, а не виртуальный хост/HTTP-домен?** В ранней версии домен
> `https://jcore.forum` отдавался через `SetVirtualHostNameToFolderMapping` +
> `WebResourceRequested`. Это работало только в WebView2 (Windows) и плюс
> маппинг глушил сам перехватчик. Для кроссплатформенности выбран путь
> «file:// + мост», одинаковый на всех ОС.

---

## Мост JS ↔ C# (Bridge)

Формат сообщений:

```
JS → C#:   { "reqId": 7, "action": "group.posts", "args": {"groupId":11,"page":1,"size":10,"search":"keyword"} }
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

Медиа и загрузки файлов идут **через мост** base64-строками
(`attachment.download`, `post.uploadFiles`), см. раздел «Медиа».

### Кэш вложений

В `api.js` objectURL-кэш по id вложения (`JFC_BLOB`) — `attachmentId → objectURL`,
чтобы повторные обращения к одному файлу не перекачивали его с бэка.
На время скачивания одноимённая карта `JFC_BLOB_PENDING` хранит `Promise`,
чтобы параллельные запросы одного вложения дождались одного скачивания.
(В `Bridge` также остался неиспользуемый LRU-кэш 6 записей из ранней версии.)

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
`api.js → mediaSrc(att)` вызывает `attachment.download` через мост, получает
`dataBase64` + `mimeType`, собирает `Blob`→objectURL:
`<img>/<video>/<audio src="blob:...">`. objectURL кэшируются в JS-карте
`attachmentId → objectURL` (`JFC_BLOB`), повторные обращения не перекачивают
файл с бэка.

**Загрузка** (создание поста, «Прикрепить файлы»):
JS читает выбранные файлы через `FileReader` в base64 и передаёт их аргументом
моста:

```js
api('post.uploadFiles', { postId, files: [{ name: file.name, dataBase64: base64 }] })
```

C# (`Bridge.FilesOf`) декодирует их в байты и передаёт на бэк
(`uploadPostFilesAction`). Файлы грузятся последовательно, прогресс
отображается на кнопке («Загрузка файлов 2/5…»); неудачные файлы не ломают
пост — показывается их список.

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
| `mediaSrc(att)` | objectURL вложения (attachment.download → base64 → Blob) |
| `uploadFileToPost/uploadFilesSequentially` | загрузка файлов base64 через мост (`post.uploadFiles`) |

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
| `group.html` | карточка группы, подписка/отписка, поиск и список постов с превью, CRUD владельца |
| `create-group.html` | создание; редактирование при `?id=` |
| `create-post.html` | создание `?groupId=` (после создания поста — загрузка файлов); редактирование `?postId=` (файлы скрыты — бэк принимает их только отдельным вызовом) |
| `post.html` | просмотр поста, галерея медиа (base64→Blob→objectURL) с лайтбоксом для фото, комментарии (CRUD, пагинация), прикрепление файлов автором |

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

* запуск и аргументы, создание/настройка веб-движка (WebView2/WebKitGTK/WKWebView);
* каждая навигация и её результат;
* каждый запрос моста (JSON, длинные значения обрезаны) и ответ;
* ошибки с полным стеком.

Если что-то «не работает» — первым делом смотреть сюда: по логу видно,
на каком шаге оборвалась цепочка (страница → мост → TCP → бэк).

---

## Самотесты

```powershell
dotnet run --project D:\AI\AiDev\ForumProject\ForumClient -- --selftest
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

1. **HTTP-перехватчик — это прошлое.** В кроссплатформенной версии
   нет ни `SetVirtualHostNameToFolderMapping`, ни `WebResourceRequested`,
   ни виртуального домена `jcore.forum`: страницы грузятся по `file://`,
   данные ходят только через мост. Не возвращайте `CoreWebView2`-код —
   на Linux/macOS его не существует.
2. **`Window.AttachedToVisualTree` может не сработать.** Первая навигация
   запускается по `Opened` + страховочному таймеру 1500 мс. Не полагайтесь
   только на одно событие.
3. **Двойное JSON-кодирование моста.** `postMessage` со *строкой*
   приходит в C# как строка-в-строке. JS шлёт объект напрямую, а C#
   дополнительно умеет разворачивать (`UnwrapIfString`).
4. **Медиа и файлы ходят через base64-мост.** `attachment.download` →
   base64 → `Blob` → objectURL; загрузка — `files: [{name, dataBase64}]`.
   Это кроссплатформенно, но большие файлы = большие JSON-строки
   (память/таймауты). Для очень тяжёлых медиа закладывайте запас RAM.
5. **objectURL-кэш в JS.** Браузер ходит по одному objectURL несколько
   раз, поэтому `api.js` кэширует `attachmentId → objectURL` (`JFC_BLOB`).
6. **Порядок параметров при файлах:** параметры → имена файлов → блок
   `логин<security>пароль` (последний!). Бэк разбирает именно так.
7. **Редактирование поста не принимает файлы** — у `updatePostAction` их
   просто нет; файлы прикрепляются со страницы поста (`uploadPostFilesAction`).
8. **PowerShell 5.1 портит UTF-8** при `Get-Content | Set-Content` для файлов
   без BOM (кириллица превращается в mojibake). Правьте файлы редактором/инструментами.
9. **Бэк грузит файл целиком в память** (до 200 МБ) и возвращает base64
   (×1.33 к размеру) — клиент тоже держит копию в кэше. Для очень тяжёлых
   медиа закладывайте запас RAM.
10. **`window.open`/`target="_blank"` в webview не создают новые окна.**
    `WebViewNewWindowRequested` (общий для всех движков) открывает ссылку
    навигацией в текущем окне (`UrlLoadingStrategy = OpenInWebView`).
    Фото просматриваются лайтбоксом `#media-viewer` (Esc/клик мимо).
11. **`post.create` возвращает id вложенно.** Формат ответа:
    `{"status":"OK","post":{"id":58,"attachmentsSaved":0}}`. Берите
    `res.post?.id`, а не `res.id` — иначе при загрузке файлов уйдёт
    запрос к `post.uploadFiles` с `postId: undefined` и бэк ответит ошибкой.

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
$env:JCORE_START_PATH='/group.html?id=11'; dotnet run --project D:\AI\AiDev\ForumProject\ForumClient
```

и смотрите `client.log` — там виден весь путь каждого запроса.
