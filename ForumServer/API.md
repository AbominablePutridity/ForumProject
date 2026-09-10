# JCore Forum — документация по API

Документация по всем роутам backend-части форума (группы, посты, комментарии, лента).
Бэкенд работает на самописном фреймворке JCore поверх **сырого TCP-сокета** (это НЕ HTTP).

---

## Оглавление

1. [Общие сведения](#1-общие-сведения)
2. [Формат запроса](#2-формат-запроса)
3. [Формат ответа](#3-формат-ответа)
4. [Как тестировать роуты](#4-как-тестировать-роуты)
5. [Роуты аутентификации — AuthController](#5-роуты-аутентификации--authcontroller)
6. [Роуты групп и подписок — GroupController](#6-роуты-групп-и-подписок--groupcontroller)
7. [Роуты постов и вложений — PostController](#7-роуты-постов-и-вложений--postcontroller)
8. [Роуты комментариев — CommentController](#8-роуты-комментариев--commentcontroller)
9. [Роут главной ленты — FeedController](#9-роут-главной-ленты--feedcontroller)
10. [Сквозной сценарий проверки (30 шагов)](#10-сквозной-сценарий-проверки-30-шагов)
11. [Ограничения и правила](#11-ограничения-и-правила)

---

## 1. Общие сведения

| Параметр | Значение |
|---|---|
| Транспорт | сырой TCP (`ServerSocket`), не HTTP |
| Адрес | `127.0.0.1` (localhost) |
| Порт | `8082` |
| Кодировка | UTF-8 |
| Модель | один запрос = одно соединение (keep-alive нет) |
| Формат ответа | одна строка (JSON либо строка ошибки) |

Запуск сервера (нужен JDK 21+ и запущенная PostgreSQL):

```bash
cd JCore

# Windows PowerShell (если java по умолчанию ниже 21):
$env:JAVA_HOME = "C:\Program Files\Java\jdk-22"   # путь к вашему JDK 21+
mvn compile exec:java

# Linux/macOS:
JAVA_HOME=/usr/lib/jvm/jdk-21 mvn compile exec:java
```

> Проект собирается с `maven.compiler.release=21`. Если в `JAVA_HOME` окажется JDK 17,
> компиляция упадет с ошибкой `release version 21 not supported`.

Подключение к БД настраивается в `JCore/src/main/java/vendor/EntityOrm/ConfigJDBC.java`
(url, пользователь, пароль). При старте сервер сам создает все таблицы (`CREATE TABLE IF NOT EXISTS`).

---

## 2. Формат запроса

### 2.1. Текстовая часть

```text
ИмяКонтроллера/имяЭкшена<endl>параметр1<endl>параметр2<endl>...<endl>
```

- Разделитель параметров — литеральная строка `<endl>` (не перевод строки!).
- Первый элемент до `<endl>` — роут (`Контроллер/экшен`), регистр важен.
- Остальные элементы попадают в массив `params` экшена **в порядке следования** —
  порядок параметров для каждого роута описан ниже в таблицах.

### 2.2. Блок аутентификации

Все роуты, кроме регистрации и входа, защищены. Такой запрос обязан содержать
**последним** параметр вида:

```text
логин<security>пароль
```

Пример защищенного запроса:

```text
FeedController/getFeedAction<endl>1<endl>10<endl>tuser1<security>pass1234<endl>
```

Если логин/пароль неверные или роли недостаточно — придет строка
`ACCESS_DENIED: ...`. Пароль в БД хранится как BCrypt-хеш, поэтому пароль в
запросе указывается в открытом виде (тот, что задавали при регистрации).

### 2.3. Бинарная часть (файлы)

Используется только в `PostController/createPostAction` и `PostController/uploadPostFilesAction`.
После текстовой части идет маркер `<BINARY>`, затем последовательно каждый файл,
затем терминатор:

```text
<BINARY>[int32 big-endian размер файла 1][байты файла 1][int32 размер файла 2][байты файла 2]...[int32 0]
```

Порядок имён файлов в текстовых параметрах должен соответствовать порядку файлов
в бинарной части.

### 2.4. Запрещенные подстроки в параметрах

| Подстрока | Почему нельзя |
|---|---|
| `<endl>` | сломает разбор параметров |
| `<security>` | сломает аутентификацию (разрешена только в самом блоке логина) |
| `<BINARY>` | воспринимается как начало бинарной части |

---

## 3. Формат ответа

Ответ — всегда **одна строка**.

Успешные операции изменения данных:

```json
{"status":"OK","message":"group created","id":5}
```

Ошибки бизнес-логики:

```json
{"status":"ERROR","message":"only the group owner can edit the group"}
```

Отказ доступа (неверный логин/пароль/роль):

```text
ACCESS_DENIED: ОШИБКА! Данные логина и пароля не верны, либо ваша роль не предусматривает получение данных по данному роуту!
```

Несуществующий роут или падение экшена:

```text
ERROR: Controller returned null
```

Списки с пагинацией (все `*Page*`-роуты):

```json
{"page":1,"size":10,"total":42,"pages":5,"items":[{ "...": "..." }]}
```

- `page` — номер страницы (с 1), `size` — размер страницы (по умолчанию 10, максимум 50),
- `total` — всего записей, `pages` — всего страниц,
- `items` — элементы текущей страницы.

---

## 4. Как тестировать роуты

### Вариант A (универсальный, рекомендуется) — класс `FileClient`

Готовый клиент `com.mycompany.jcore.client.FileClient` подходит для ЛЮБОГО роута:
он всегда добавляет корректную бинарную обертку (даже без файлов).

```java
import com.mycompany.jcore.client.FileClient;
import java.io.File;

public class Demo {
    public static void main(String[] args) throws Exception {
        FileClient client = new FileClient("127.0.0.1", 8082);

        // текстовый запрос (файлов нет):
        System.out.println(client.sendFile(
                "AuthController", "loginAction",
                new String[]{"tuser1", "pass1234"}
        ));

        // запрос с защитой:
        System.out.println(client.sendFile(
                "GroupController", "createGroupAction",
                new String[]{"Моя группа", "Описание группы", "tuser1<security>pass1234"}
        ));

        // загрузка поста с двумя файлами:
        System.out.println(client.sendFile(
                "PostController", "createPostAction",
                new String[]{"1", "Заголовок", "Текст поста",
                             "photo.jpg", "clip.mp4",          // имена файлов по порядку
                             "tuser1<security>pass1234"},
                new File("photo.jpg"), new File("clip.mp4")
        ));
    }
}
```

### Вариант B — минимальный свой клиент (только текст)

Важно: для текстового запроса нужно закрыть исходящий поток (`shutdownOutput`),
иначе сервер будет ждать конца запроса.

```java
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TcpDemo {
    public static void main(String[] args) throws Exception {
        String request = "AuthController/loginAction<endl>tuser1<endl>pass1234<endl>";
        try (Socket s = new Socket("127.0.0.1", 8082)) {
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.shutdownOutput();                                  // сигнал "запрос закончен"
            System.out.println(new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
```

### Вариант C — PuTTY / telnet (raw)

1. PuTTY: Host `127.0.0.1`, Port `8082`, Connection type: *Other → Raw*, Close window on exit: *Never*.
2. Ввести строку запроса целиком и нажать Enter.
3. Нажать закрытие соединения (иконка в заголовке окна → Close) — сервер дождется EOF,
   обработает запрос и вернет ответ перед закрытием.

telnet: `telnet 127.0.0.1 8082`, далее аналогично (закрыть сессию через `Ctrl+]`, затем `quit`).

> Из-за того, что сервер читает запрос до конца потока, для чисто текстовых запросов
> варианты A/B удобнее: они сами закрывают исходящий поток.

---

## 5. Роуты аутентификации — AuthController

Оба роута публичные (блок `<security>` НЕ нужен).

### 5.1. Регистрация — `AuthController/registerAction`

| # | Параметр | Ограничения |
|---|---|---|
| 1 | name | непустой, ≤ 300 символов |
| 2 | surname | непустое, ≤ 300 символов |
| 3 | login | непустой, ≤ 100 символов, уникальный |
| 4 | password | 4–72 символа |

Роль присваивается автоматически — `USER`. Пароль сохраняется как BCrypt-хеш.

Запрос:

```text
AuthController/registerAction<endl>Ivan<endl>Ivanov<endl>tuser1<endl>pass1234<endl>
```

Ответ:

```json
{"status":"OK","message":"user registered"}
```

Ошибки: `login already taken`, `password must be at least 4 characters`,
`name is required`, `login is too long (max 100)` и т.п.

### 5.2. Вход — `AuthController/loginAction`

| # | Параметр |
|---|---|
| 1 | login |
| 2 | password |

Запрос:

```text
AuthController/loginAction<endl>tuser1<endl>pass1234<endl>
```

Ответ:

```json
{"status":"OK","user":{"id":6,"name":"Ivan","surname":"Ivanov","login":"tuser1","role":"USER"}}
```

`id` из ответа — ваш идентификатор пользователя (автор постов/комментариев/групп).
Ошибка: `{"status":"ERROR","message":"invalid login or password"}`.

---

## 6. Роуты групп и подписок — GroupController

Все роуты требуют блок `<security>` последним параметром (роль `USER`).

### 6.1. Создание группы — `GroupController/createGroupAction`

| # | Параметр | Ограничения |
|---|---|---|
| 1 | title | непустой, ≤ 300 символов |
| 2 | description | необязателен, ≤ 10000 символов |
| 3 | блок `<security>` | — |

Автор группы = текущий пользователь. Посты в группе может создавать только ее автор.

Запрос:

```text
GroupController/createGroupAction<endl>Java Fans<endl>Группа про Java<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"status":"OK","message":"group created","id":1}
```

### 6.2. Редактирование группы — `GroupController/updateGroupAction` (только владелец)

| # | Параметр |
|---|---|
| 1 | groupId |
| 2 | title (новый) |
| 3 | description (новое) |
| 4 | блок `<security>` |

Запрос:

```text
GroupController/updateGroupAction<endl>1<endl>Новое название<endl>Новое описание<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"group updated","id":1}`
Чужая группа: `{"status":"ERROR","message":"only the group owner can edit the group"}`

### 6.3. Удаление группы — `GroupController/deleteGroupAction` (только владелец)

| # | Параметр |
|---|---|
| 1 | groupId |
| 2 | блок `<security>` |

Каскадно удаляет все посты, комментарии и записи вложений группы;
файлы вложений удаляются с диска.

Запрос:

```text
GroupController/deleteGroupAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"group deleted","id":1}`

### 6.4. Просмотр группы — `GroupController/getGroupAction`

| # | Параметр |
|---|---|
| 1 | groupId |
| 2 | блок `<security>` |

Запрос:

```text
GroupController/getGroupAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"status":"OK","group":{"id":1,"title":"Java Fans","description":"Группа про Java","createdAt":"2026-08-21 12:32:41.34779","ownerId":6,"ownerLogin":"tuser1","subscribersCount":1,"postsCount":2}}
```

### 6.5. Посты группы с пагинацией — `GroupController/getGroupPostsAction`

| # | Параметр |
|---|---|
| 1 | groupId |
| 2 | page (с 1) |
| 3 | size (1–50) |
| 4 | search (опционально) — строка поиска по заголовку или тексту поста |
| последний | блок `<security>` |

Параметр `search` — необязателен. Если передан, выполняется поиск по заголовку (`title`)
и тексту (`body`) постов с помощью оператора `ILIKE` (регистронезависимый поиск подстроки).
Совпадение подстроки может находиться в любом месте заголовка или текста.
Если `search` не передан или пуст — возвращаются все посты группы без фильтрации.

Запрос без поиска (страница 1, по 1 посту):

```text
GroupController/getGroupPostsAction<endl>1<endl>1<endl>1<endl>tuser1<security>pass1234<endl>
```

Запрос с поиском:

```text
GroupController/getGroupPostsAction<endl>1<endl>1<endl>10<endl>Java<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"page":1,"size":1,"total":2,"pages":2,"items":[{"id":2,"title":"Post with media","body":"Look at this","createdAt":"2026-08-21 12:32:41.860064","groupId":1,"groupTitle":"Java Fans","authorId":6,"authorLogin":"tuser1","commentsCount":0,"attachmentsCount":2}]}
```

Сортировка: новые сверху (`createdAt DESC`). `commentsCount`/`attachmentsCount` — счетчики для кнопки «открыть пост».

### 6.6. Поиск всех групп — `GroupController/searchGroupsAction`

Каталог групп для подписки. Параметры: `page`, `size`, `search` (опционально), блок `<security>`.

Параметр `search` — необязателен. Если передан, выполняется поиск по названию (`title`)
группы с помощью оператора `ILIKE` (регистронезависимый поиск подстроки).
Совпадение подстроки может находиться в любом месте названия группы.
Если `search` не передан или пуст — возвращаются все группы без фильтрации.

Запрос без поиска:

```text
GroupController/searchGroupsAction<endl>1<endl>10<endl>tuser1<security>pass1234<endl>
```

Запрос с поиском (ищем группы, содержащие "Java" в названии):

```text
GroupController/searchGroupsAction<endl>1<endl>10<endl>Java<endl>tuser1<security>pass1234<endl>
```

Ответ — пагинированный список, элемент как в `getGroupAction`
(`id, title, description, createdAt, ownerId, ownerLogin, subscribersCount, postsCount`).

### 6.7. Мои группы — `GroupController/getMyGroupsAction`

Группы, созданные текущим пользователем. Параметры: только блок `<security>`.

Запрос:

```text
GroupController/getMyGroupsAction<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"status":"OK","groups":[{"id":1,"title":"Java Fans","description":"...","createdAt":"...","ownerId":6,"ownerLogin":"tuser1","subscribersCount":1,"postsCount":2}]}
```

### 6.8. Подписки — `GroupController/getSubscribedGroupsAction`

Группы, на которые подписан пользователь. Параметры: только блок `<security>`.

Запрос:

```text
GroupController/getSubscribedGroupsAction<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","groups":[{ "id", "title", "description", "createdAt", "ownerId", "ownerLogin", "subscribersCount" }]}`

### 6.9. Подписаться — `GroupController/subscribeAction`

| # | Параметр |
|---|---|
| 1 | groupId |
| 2 | блок `<security>` |

Запрос:

```text
GroupController/subscribeAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответы: `{"status":"OK","message":"subscribed","id":1}` либо
`{"status":"OK","message":"already subscribed","id":1}` (повторная подписка идемпотентна).

### 6.10. Отписаться — `GroupController/unsubscribeAction`

Параметры те же: `groupId`, блок `<security>`.

Запрос:

```text
GroupController/unsubscribeAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"unsubscribed","id":1}` (идемпотентно — отписка от неподписанной группы тоже вернет OK).

---

## 7. Роуты постов и вложений — PostController

Все роуты требуют блок `<security>` последним параметром (роль `USER`).

### 7.1. Создание поста — `PostController/createPostAction` (только в своей группе)

| # | Параметр | Примечание |
|---|---|---|
| 1 | groupId | группа должна принадлежать вам |
| 2 | title | непустой, ≤ 300 символов |
| 3 | body | текст поста, может быть пустым, ≤ 100000 символов |
| 4..N | имена файлов (необязательно) | по одному параметру на файл, порядок = порядку файлов в бинарной части |
| последний | блок `<security>` | — |

Файлы передаются в бинарной части (см. раздел 2.3): фото/видео, сколько угодно,
каждый до 200 МБ. Если имя для файла не передано — ему будет выдано имя `fileN.bin`.
MIME-тип определяется по расширению имени файла.

Запрос без файлов:

```text
PostController/createPostAction<endl>1<endl>Первый пост<endl>Привет, мир!<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"status":"OK","post":{"id":1,"attachmentsSaved":0}}
```

Запрос с двумя файлами (текст + `<BINARY>` + `[size][bytes][size][bytes][int 0]`):

```text
PostController/createPostAction<endl>1<endl>Пост с медиа<endl>Смотрите что нашел<endl>photo1.jpg<endl>clip.mp4<endl>tuser1<security>pass1234<endl><BINARY>...
```

Ответ: `{"status":"OK","post":{"id":2,"attachmentsSaved":2}}`

Чужая группа: `{"status":"ERROR","message":"posts can be created only in your own group"}`

### 7.2. Редактирование поста — `PostController/updatePostAction` (только автор)

| # | Параметр |
|---|---|
| 1 | postId |
| 2 | title (новый) |
| 3 | body (новый) |
| 4 | блок `<security>` |

Запрос:

```text
PostController/updatePostAction<endl>1<endl>Обновленный заголовок<endl>Обновленный текст<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"post updated","id":1}`
Чужой пост: `{"status":"ERROR","message":"only the post author can edit the post"}`

### 7.3. Удаление поста — `PostController/deletePostAction` (только автор)

| # | Параметр |
|---|---|
| 1 | postId |
| 2 | блок `<security>` |

Каскадно удаляет комментарии и записи вложений; файлы вложений удаляются с диска.

Запрос:

```text
PostController/deletePostAction<endl>2<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"post deleted","id":2}`

### 7.4. Просмотр поста — `PostController/getPostAction`

Открытие конкретного поста: данные поста + метаданные всех вложений
(для отрисовки галереи фото/видео и списка комментариев).

| # | Параметр |
|---|---|
| 1 | postId |
| 2 | блок `<security>` |

Запрос:

```text
PostController/getPostAction<endl>2<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"status":"OK","post":{"id":2,"title":"Пост с медиа","body":"Смотрите что нашел","createdAt":"2026-08-21 12:32:41.860064","groupId":1,"groupTitle":"Java Fans","authorId":6,"authorLogin":"tuser1","commentsCount":0,"attachmentsCount":1},"attachments":[{"id":2,"postId":2,"fileName":"clip.mp4","mimeType":"video/mp4","fileSize":43227}]}
```

### 7.5. Дозагрузка файлов к посту — `PostController/uploadPostFilesAction` (только автор)

| # | Параметр |
|---|---|
| 1 | postId |
| 2..N | имена файлов (по порядку бинарной части) |
| последний | блок `<security>` |

Запрос:

```text
PostController/uploadPostFilesAction<endl>2<endl>extra_photo.png<endl>tuser1<security>pass1234<endl><BINARY>[размер][байты][int 0]
```

Ответ: `{"status":"OK","message":"attachments saved: 1","id":2}`

### 7.6. Удаление вложения — `PostController/deleteAttachmentAction` (только автор поста)

| # | Параметр |
|---|---|
| 1 | attachmentId |
| 2 | блок `<security>` |

Запрос:

```text
PostController/deleteAttachmentAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"attachment deleted","id":1}`
Чужой пост: `{"status":"ERROR","message":"only the post author can delete attachments"}`

### 7.7. Скачивание вложения — `PostController/downloadAttachmentAction`

Возвращает содержимое файла в base64 (транспорт фреймворка — текстовый протокол,
поэтому бинарные данные кодируются).

| # | Параметр |
|---|---|
| 1 | attachmentId |
| 2 | блок `<security>` |

Запрос:

```text
PostController/downloadAttachmentAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответ (base64-строка обрезана для примера):

```json
{"status":"OK","id":1,"postId":2,"fileName":"photo1.jpg","mimeType":"image/jpeg","fileSize":43227,"dataBase64":"/9j/4AAQSkZJRgABAQEBLAEsAAD/..."}
```

На клиенте: декодировать `dataBase64` из base64 → получить исходные байты файла.

---

## 8. Роуты комментариев — CommentController

Все роуты требуют блок `<security>` последним параметром (роль `USER`).

### 8.1. Создание комментария — `CommentController/createCommentAction` (любой авторизованный)

| # | Параметр | Ограничения |
|---|---|---|
| 1 | postId | пост должен существовать |
| 2 | body | непустой, ≤ 10000 символов |
| 3 | блок `<security>` | — |

Комментировать можно посты в любых группах, подписка не требуется.

Запрос:

```text
CommentController/createCommentAction<endl>1<endl>Отличный пост!<endl>tuser2<security>pass5678<endl>
```

Ответ: `{"status":"OK","message":"comment created","id":2}`

### 8.2. Редактирование комментария — `CommentController/updateCommentAction` (только автор комментария)

| # | Параметр |
|---|---|
| 1 | commentId |
| 2 | body (новый текст) |
| 3 | блок `<security>` |

Запрос:

```text
CommentController/updateCommentAction<endl>1<endl>Исправленный комментарий<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"comment updated","id":1}`
Чужой комментарий: `{"status":"ERROR","message":"only the comment author can edit the comment"}`

### 8.3. Удаление комментария — `CommentController/deleteCommentAction` (только автор комментария)

| # | Параметр |
|---|---|
| 1 | commentId |
| 2 | блок `<security>` |

Запрос:

```text
CommentController/deleteCommentAction<endl>1<endl>tuser1<security>pass1234<endl>
```

Ответ: `{"status":"OK","message":"comment deleted","id":1}`

### 8.4. Комментарии поста с пагинацией — `CommentController/getCommentsAction`

| # | Параметр |
|---|---|
| 1 | postId |
| 2 | page |
| 3 | size |
| 4 | блок `<security>` |

Запрос:

```text
CommentController/getCommentsAction<endl>1<endl>1<endl>10<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"page":1,"size":10,"total":1,"pages":1,"items":[{"id":1,"body":"Отличный пост!","createdAt":"2026-08-21 12:32:42.886823","postId":1,"authorId":6,"authorLogin":"tuser1"}]}
```

Сортировка: старые сверху (`createdAt ASC`) — привычная лента обсуждения.

---

## 9. Роут главной ленты — FeedController

### 9.1. Лента подписок — `FeedController/getFeedAction`

Последние посты **всех групп, на которые подписан пользователь**, новые сверху.

| # | Параметр |
|---|---|
| 1 | page |
| 2 | size |
| 3 | блок `<security>` |

Запрос:

```text
FeedController/getFeedAction<endl>1<endl>10<endl>tuser1<security>pass1234<endl>
```

Ответ:

```json
{"page":1,"size":10,"total":2,"pages":1,"items":[{"id":2,"title":"Пост с медиа","body":"Смотрите что нашел","createdAt":"2026-08-21 12:32:41.860064","groupId":1,"groupTitle":"Java Fans","authorId":6,"authorLogin":"tuser1","commentsCount":0,"attachmentsCount":2},{"id":1,"title":"Первый пост","body":"Привет, мир!","createdAt":"2026-08-21 12:32:41.605697","groupId":1,"groupTitle":"Java Fans","authorId":6,"authorLogin":"tuser1","commentsCount":1,"attachmentsCount":0}]}
```

Если подписок нет — `{"page":1,"size":10,"total":0,"pages":0,"items":[]}`.
Чтобы лента наполнилась: подпишитесь на группу через `GroupController/subscribeAction`
(на свою группу тоже можно подписаться).

---

## 10. Сквозной сценарий проверки (32 шага)

Готовая последовательность «под ключ»: два пользователя, группа, посты с файлами,
подписки, лента, комментарии, проверка запретов прав, поиск по группам и постам.
Логины `tuser1`/`tuser2` замените на свои уникальные (в тестовой БД уже могут существовать
`ivanov`, `petrova` и др.).

Шаги 1–13 выполняются от `tuser1`, 14–24 — от `tuser2`, далее смешанно.

| № | Что проверяем | Запрос | Ожидаемый ответ (ключевое) |
|---|---|---|---|
| 1 | Регистрация | `AuthController/registerAction<endl>Ivan<endl>Ivanov<endl>tuser1<endl>pass1234<endl>` | `"message":"user registered"` |
| 2 | Дубликат логина | шаг 1 повторить | `"ERROR","message":"login already taken"` |
| 3 | Вход с неверным паролем | `AuthController/loginAction<endl>tuser1<endl>badpass<endl>` | `"invalid login or password"` |
| 4 | Вход верный | `AuthController/loginAction<endl>tuser1<endl>pass1234<endl>` | `"status":"OK","user":{...}` |
| 5 | Создание группы | `GroupController/createGroupAction<endl>Java Fans<endl>Про Java<endl>tuser1<security>pass1234<endl>` | `"group created","id":1` |
| 6 | Пост без файлов | `PostController/createPostAction<endl>1<endl>Первый пост<endl>Привет!<endl>tuser1<security>pass1234<endl>` | `"id":1,"attachmentsSaved":0` |
| 7 | Пост с 2 файлами | `PostController/createPostAction<endl>1<endl>С медиа<endl>Смотрите<endl>photo1.jpg<endl>photo2.jpg<endl>tuser1<security>pass1234<endl>` + `<BINARY>` 2 файла | `"id":2,"attachmentsSaved":2` |
| 8 | Подписка на группу | `GroupController/subscribeAction<endl>1<endl>tuser1<security>pass1234<endl>` | `"subscribed"` |
| 9 | Лента | `FeedController/getFeedAction<endl>1<endl>10<endl>tuser1<security>pass1234<endl>` | `total:2`, новые сверху |
| 10 | Пагинация постов группы | `GroupController/getGroupPostsAction<endl>1<endl>1<endl>1<endl>tuser1<security>pass1234<endl>` | `size:1,total:2,pages:2` |
| 10a | Поиск постов по тексту | `GroupController/getGroupPostsAction<endl>1<endl>1<endl>10<endl>media<endl>tuser1<security>pass1234<endl>` | `total:1`, пост "Post with media" |
| 10b | Поиск групп по названию | `GroupController/searchGroupsAction<endl>1<endl>10<endl>Java<endl>tuser1<security>pass1234<endl>` | `total:1`, группа "Java Fans" |
| 11 | Комментарий | `CommentController/createCommentAction<endl>1<endl>Отлично!<endl>tuser1<security>pass1234<endl>` | `"comment created","id":1` |
| 12 | Список комментариев | `CommentController/getCommentsAction<endl>1<endl>1<endl>10<endl>tuser1<security>pass1234<endl>` | items c телом и автором |
| 13 | Правка своего комментария | `CommentController/updateCommentAction<endl>1<endl>Исправлено<endl>tuser1<security>pass1234<endl>` | `"comment updated"` |
| 14 | Второй пользователь | `AuthController/registerAction<endl>Petra<endl>Petrova<endl>tuser2<endl>pass5678<endl>` | `"user registered"` |
| 15 | Чужую группу править нельзя | `GroupController/updateGroupAction<endl>1<endl>Hack<endl>Hack<endl>tuser2<security>pass5678<endl>` | `"only the group owner can edit the group"` |
| 16 | Пост в чужой группе нельзя | `PostController/createPostAction<endl>1<endl>X<endl>X<endl>tuser2<security>pass5678<endl>` | `"posts can be created only in your own group"` |
| 17 | Подписка tuser2 | `GroupController/subscribeAction<endl>1<endl>tuser2<security>pass5678<endl>` | `"subscribed"` |
| 18 | Лента tuser2 видит чужие посты | `FeedController/getFeedAction<endl>1<endl>10<endl>tuser2<security>pass5678<endl>` | `total:2` |
| 19 | Комментарий чужого поста можно | `CommentController/createCommentAction<endl>1<endl>Tuser2 был здесь<endl>tuser2<security>pass5678<endl>` | `"comment created","id":2` |
| 20 | Чужой комментарий править нельзя | `CommentController/updateCommentAction<endl>1<endl>Hack<endl>tuser2<security>pass5678<endl>` | `"only the comment author can edit..."` |
| 21 | Чужое вложение удалить нельзя | `PostController/deleteAttachmentAction<endl>1<endl>tuser2<security>pass5678<endl>` | `"only the post author can delete attachments"` |
| 22 | Отписка | `GroupController/unsubscribeAction<endl>1<endl>tuser2<security>pass5678<endl>` | `"unsubscribed"` |
| 23 | Лента после отписки пуста | `FeedController/getFeedAction<endl>1<endl>10<endl>tuser2<security>pass5678<endl>` | `total:0,"items":[]` |
| 24 | Скачивание вложения | `PostController/downloadAttachmentAction<endl>1<endl>tuser1<security>pass1234<endl>` | `"dataBase64":"/9j/..."` |
| 25 | Удаление вложения владельцем | `PostController/deleteAttachmentAction<endl>1<endl>tuser1<security>pass1234<endl>` | `"attachment deleted"` |
| 26 | Неавторизованный доступ запрещен | `FeedController/getFeedAction<endl>1<endl>10<endl>nobody<security>nothing<endl>` | `ACCESS_DENIED: ...` |
| 27 | Каталог групп | `GroupController/searchGroupsAction<endl>1<endl>10<endl>tuser1<security>pass1234<endl>` | список с `subscribersCount` |
| 27a | Поиск групп | `GroupController/searchGroupsAction<endl>1<endl>10<endl>Java<endl>tuser1<security>pass1234<endl>` | `total:1`, группа "Java Fans" |
| 28 | Мои группы | `GroupController/getMyGroupsAction<endl>tuser1<security>pass1234<endl>` | `"groups":[...]` |
| 29 | Просмотр поста с вложениями | `PostController/getPostAction<endl>2<endl>tuser1<security>pass1234<endl>` | `"post":{...},"attachments":[...]` |
| 30 | Удаление поста (+очистка файлов) | `PostController/deletePostAction<endl>2<endl>tuser1<security>pass1234<endl>` | `"post deleted"` |

Для шагов 7 и 24 удобно использовать `FileClient` (см. раздел 4, вариант A).

---

## 11. Ограничения и правила

| Ограничение | Значение |
|---|---|
| Размер одного файла | ≤ 200 МБ (фреймворк грузит файл в память целиком) |
| Страница (`size`) | 1–50, по умолчанию 10 |
| title (группа/пост) | ≤ 300 символов |
| name / surname | ≤ 300 символов |
| login | ≤ 100 символов, уникальный |
| password | 4–72 символа (хранится BCrypt-хеш) |
| description группы | ≤ 10000 символов |
| тело поста | ≤ 100000 символов |
| комментарий | ≤ 10000 символов |

Правила доступа (сводка):

| Действие | Кто может |
|---|---|
| Создавать/редактировать/удалять группу | только владелец группы |
| Создавать посты | только владелец группы, только в своей группе |
| Редактировать/удалять пост | только автор поста (= владелец группы) |
| Управлять вложениями поста | только автор поста |
| Создавать комментарии | любой авторизованный пользователь |
| Редактировать/удалять комментарий | только автор комментария |
| Подписываться/читать ленту, группы, посты, комментарии | любой авторизованный пользователь |

Особенности протокола, о которых стоит помнить:

- Ответ приходит одной строкой; клиент читает поток до закрытия соединения сервером.
- Для текстовых запросов клиент должен закрыть исходящий поток после отправки
  (`shutdownOutput()`), иначе сервер будет ждать продолжения запроса.
- Одновременно сервер обрабатывает до 4 запросов (пул воркеров фреймворка),
  тяжелые аплоады занимают воркера на все время приема файла.
