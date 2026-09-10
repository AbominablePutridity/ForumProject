using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;

namespace ForumClient.Services;

/// <summary>Вложение, разобранное для отдачи веб-оболочке сырыми байтами.</summary>
public sealed record AttachmentMedia(string FileName, string MimeType, byte[] Data);

/// <summary>
/// Мост между JS-страницами и JCoreApiClient. JS-страницы общаются с C#
/// через кроссплатформенный канал (chrome.webview на Windows /
/// webkit.messageHandlers.webview на Linux/macOS / window.external).
///   Формат запроса от JS:   { "reqId": 1, "action": "feed.get", "args": {...} }
///   Формат ответа в JS:     { "reqId": 1, "ok": true, "data": {...} }
///                     или { "reqId": 1, "ok": false, "error": "..." }
/// </summary>
public sealed class Bridge
{
    private readonly JCoreApiClient _api = new(
        Environment.GetEnvironmentVariable("JCORE_HOST") ?? "127.0.0.1",
        int.TryParse(Environment.GetEnvironmentVariable("JCORE_PORT"), out int port) ? port : 8082);

    // Простой LRU-кэш вложений: видео запрашивается браузером несколькими
    // Range-запросами, и без кэша файл скачивался бы с бэкенда каждый раз.
    private const int MediaCacheCapacity = 6;
    private readonly object _mediaCacheSync = new();
    private readonly Dictionary<long, AttachmentMedia> _mediaCache = new();
    private readonly List<long> _mediaCacheOrder = new();

    public async Task<string> HandleAsync(string jsonRequest)
    {
        long reqId = -1;

        try
        {
            using JsonDocument doc = JsonDocument.Parse(jsonRequest);
            JsonElement root = UnwrapIfString(doc.RootElement);

            reqId = root.TryGetProperty("reqId", out JsonElement idEl) ? idEl.GetInt64() : -1;

            string action = root.TryGetProperty("action", out JsonElement actionEl)
                ? actionEl.GetString() ?? ""
                : "";

            JsonElement args = root.TryGetProperty("args", out JsonElement argsEl) && argsEl.ValueKind == JsonValueKind.Object
                ? argsEl.Clone()
                : EmptyObject();

            ApiResult result = await DispatchAsync(action, args).ConfigureAwait(false);

            Log.WriteShort("Bridge: ответ", JsonSerializer.Serialize(
                new Dictionary<string, object?>
                {
                    ["reqId"] = reqId,
                    ["ok"] = result.Success,
                    ["error"] = result.Error
                }), 200);

            return result.Success
                ? Serialize(reqId, ok: true, data: result.Data, error: null)
                : Serialize(reqId, ok: false, data: null, error: result.Error);
        }
        catch (Exception ex)
        {
            Log.Write("Bridge: ОШИБКА обработки " + ex);
            return Serialize(reqId, ok: false, data: null, error: ex.Message);
        }
    }

    private static string Serialize(long reqId, bool ok, JsonElement? data, string? error)
    {
        var payload = new Dictionary<string, object?>
        {
            ["reqId"] = reqId,
            ["ok"] = ok,
            ["data"] = data,
            ["error"] = error
        };

        return JsonSerializer.Serialize(payload);
    }

    private async Task<ApiResult> DispatchAsync(string action, JsonElement a)
    {
        switch (action)
        {
            case "session.get":
                if (!AppSession.IsAuthenticated)
                {
                    return ApiResult.Ok(Null());
                }

                return ApiResult.Ok(JsonSerializer.SerializeToElement(new
                {
                    id = AppSession.UserId,
                    name = AppSession.Name,
                    surname = AppSession.Surname,
                    login = AppSession.Login,
                    role = AppSession.Role
                }));

            // ---- auth ----
            case "auth.login":
                return await _api.LoginAsync(Str(a, "login"), Str(a, "password"));

            case "auth.logout":
                return await _api.LogoutAsync();

            case "auth.register":
                return await _api.RegisterAsync(
                    Str(a, "name"), Str(a, "surname"), Str(a, "login"), Str(a, "password"));

            // ---- feed ----
            case "feed.get":
                return await _api.GetFeedAsync(Int(a, "page"), Int(a, "size"));

            // ---- groups ----
            case "group.get":
                return await _api.GetGroupAsync(Long(a, "groupId"));

            case "group.posts":
                return await _api.GetGroupPostsAsync(Long(a, "groupId"), Int(a, "page"), Int(a, "size"), StrOrNull(a, "search"));

            case "group.search":
                return await _api.SearchGroupsAsync(Int(a, "page"), Int(a, "size"), StrOrNull(a, "search"));

            case "group.my":
                return await _api.GetMyGroupsAsync();

            case "group.subscribed":
                return await _api.GetSubscribedGroupsAsync();

            case "group.create":
                return await _api.CreateGroupAsync(Str(a, "title"), Str(a, "description"));

            case "group.update":
                return await _api.UpdateGroupAsync(
                    Long(a, "groupId"), Str(a, "title"), Str(a, "description"));

            case "group.delete":
                return await _api.DeleteGroupAsync(Long(a, "groupId"));

            case "group.subscribe":
                return await _api.SubscribeAsync(Long(a, "groupId"));

            case "group.unsubscribe":
                return await _api.UnsubscribeAsync(Long(a, "groupId"));

            // ---- posts & attachments ----
            case "post.get":
                return await _api.GetPostAsync(Long(a, "postId"));

            case "post.create":
                return await _api.CreatePostAsync(
                    Long(a, "groupId"), Str(a, "title"), Str(a, "body"), FilesOf(a));

            case "post.update":
                return await _api.UpdatePostAsync(
                    Long(a, "postId"), Str(a, "title"), Str(a, "body"));

            case "post.delete":
                return await _api.DeletePostAsync(Long(a, "postId"));

            case "post.uploadFiles":
                return await _api.UploadPostFilesAsync(
                    Long(a, "postId"), FilesOf(a) ?? Array.Empty<UploadFile>());

            case "attachment.download":
                return await _api.DownloadAttachmentAsync(Long(a, "attachmentId"));

            case "attachment.delete":
                return await _api.DeleteAttachmentAsync(Long(a, "attachmentId"));

            // ---- comments ----
            case "comment.list":
                return await _api.GetCommentsAsync(Long(a, "postId"), Int(a, "page"), Int(a, "size"));

            case "comment.create":
                return await _api.CreateCommentAsync(Long(a, "postId"), Str(a, "body"));

            case "comment.update":
                return await _api.UpdateCommentAsync(Long(a, "commentId"), Str(a, "body"));

            case "comment.delete":
                return await _api.DeleteCommentAsync(Long(a, "commentId"));

            default:
                return ApiResult.Fail("Неизвестное действие моста: " + action);
        }
    }

    /// <summary>
    /// Скачивает вложение с бэкенда и возвращает его сырые байты
    /// (для отдачи веб-оболочке через перехват https://jcore.forum/media/{id}).
    /// Результат кэшируется в памяти: браузер запрашивает видео несколькими
    /// Range-запросами, и без кэша файл скачивался бы с бэка каждый раз.
    /// </summary>
    public async Task<AttachmentMedia?> GetAttachmentMediaAsync(long attachmentId)
    {
        lock (_mediaCacheSync)
        {
            if (_mediaCache.TryGetValue(attachmentId, out AttachmentMedia? cached))
            {
                _mediaCacheOrder.Remove(attachmentId);
                _mediaCacheOrder.Add(attachmentId);
                return cached;
            }
        }

        ApiResult result = await _api.DownloadAttachmentAsync(attachmentId).ConfigureAwait(false);

        if (!result.Success || result.Data is not { } data)
        {
            return null;
        }

        string base64 = JCoreApiClient.GetString(data, "dataBase64");

        var media = new AttachmentMedia(
            JCoreApiClient.GetString(data, "fileName"),
            JCoreApiClient.GetString(data, "mimeType"),
            base64.Length == 0 ? Array.Empty<byte>() : Convert.FromBase64String(base64));

        lock (_mediaCacheSync)
        {
            if (!_mediaCache.ContainsKey(attachmentId))
            {
                _mediaCache[attachmentId] = media;
                _mediaCacheOrder.Add(attachmentId);

                while (_mediaCache.Count > MediaCacheCapacity)
                {
                    long evicted = _mediaCacheOrder[0];
                    _mediaCacheOrder.RemoveAt(0);
                    _mediaCache.Remove(evicted);
                }
            }
        }

        return media;
    }

    /// <summary>
    /// Загружает один файл к посту, прочитав тело запроса веб-оболочки
    /// (перехват POST https://jcore.forum/upload/{postId}/{fileName}) без base64.
    /// </summary>
    public async Task<ApiResult> UploadToPostAsync(long postId, string fileName, Stream content)
    {
        using MemoryStream buffer = new();

        await content.CopyToAsync(buffer).ConfigureAwait(false);

        return await _api.UploadPostFilesAsync(
            postId,
            new[] { new UploadFile(fileName, buffer.ToArray()) }).ConfigureAwait(false);
    }

    private static JsonElement UnwrapIfString(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.String)
        {
            return element;
        }

        using JsonDocument inner = JsonDocument.Parse(element.GetString() ?? "{}");
        return inner.RootElement.Clone();
    }

    private static JsonElement Null() => JsonSerializer.SerializeToElement<object?>(null);

    private static JsonElement EmptyObject()
    {
        using JsonDocument doc = JsonDocument.Parse("{}");
        return doc.RootElement.Clone();
    }

    private static string Str(JsonElement el, string key)
    {
        return el.ValueKind == JsonValueKind.Object &&
               el.TryGetProperty(key, out JsonElement v) &&
               v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? ""
            : "";
    }

    private static string? StrOrNull(JsonElement el, string key)
    {
        if (el.ValueKind != JsonValueKind.Object ||
            !el.TryGetProperty(key, out JsonElement v) ||
            v.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        string? s = v.GetString();
        return string.IsNullOrWhiteSpace(s) ? null : s;
    }

    private static int Int(JsonElement el, string key, int fallback = 1)
    {
        return el.ValueKind == JsonValueKind.Object &&
               el.TryGetProperty(key, out JsonElement v) &&
               v.ValueKind == JsonValueKind.Number &&
               v.TryGetInt32(out int n)
            ? n
            : fallback;
    }

    private static long Long(JsonElement el, string key)
    {
        if (el.ValueKind != JsonValueKind.Object ||
            !el.TryGetProperty(key, out JsonElement v))
        {
            return 0;
        }

        if (v.ValueKind == JsonValueKind.Number)
        {
            return v.TryGetInt64(out long n) ? n : 0;
        }

        if (v.ValueKind == JsonValueKind.String && long.TryParse(v.GetString(), out long parsed))
        {
            return parsed;
        }

        return 0;
    }

    private static UploadFile[]? FilesOf(JsonElement el)
    {
        if (el.ValueKind != JsonValueKind.Object ||
            !el.TryGetProperty("files", out JsonElement arr) ||
            arr.ValueKind != JsonValueKind.Array ||
            arr.GetArrayLength() == 0)
        {
            return null;
        }

        List<UploadFile> files = new();

        foreach (JsonElement f in arr.EnumerateArray())
        {
            string name = Str(f, "name");
            string base64 = Str(f, "dataBase64");

            files.Add(new UploadFile(
                name.Length == 0 ? "file.bin" : name,
                base64.Length == 0 ? Array.Empty<byte>() : Convert.FromBase64String(base64)));
        }

        return files.ToArray();
    }
}
