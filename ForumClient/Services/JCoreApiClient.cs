using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace ForumClient.Services;

/// <summary>Файл для бинарной части запроса (PostController).</summary>
public sealed record UploadFile(string FileName, byte[] Content);

/// <summary>
/// Единый клиент бэкенда JCore: один класс с методами под каждый эндпоинт.
/// Транспорт — сырой TCP (НЕ HTTP), формат запроса:
///
///   Контроллер/экшен&lt;endl&gt;параметр1&lt;endl&gt;параметр2...&lt;endl&gt;&lt;BINARY&gt;[int32 len][bytes]...[int32 0]
///
/// Защищенные роуты требуют последний параметр вида логин&lt;security&gt;пароль.
/// Ответ сервера — одна строка (JSON или текст ошибки), читается до закрытия сокета.
///
/// Использование со страниц: страницы дергают Bridge -> этот класс.
/// Использование из C#: new JCoreApiClient().GetFeedAsync(1, 10) и т.д.
/// </summary>
public sealed class JCoreApiClient
{
    public const string Endl = "<endl>";
    public const string Security = "<security>";
    public const string BinaryMarker = "<BINARY>";

    private static readonly byte[] Terminator = { 0, 0, 0, 0 };

    public string Host { get; }

    public int Port { get; }

    public JCoreApiClient(string host = "127.0.0.1", int port = 8082)
    {
        Host = host;
        Port = port;
    }

    // ------------------------------------------------------------------
    // Низкий уровень: сборка запроса и обмен по TCP
    // ------------------------------------------------------------------

    /// <summary>
    /// Отправляет произвольный запрос. parameters — параметры в порядке следования
    /// (для защищенных роутов последним идет блок авторизации). files — необязательная
    /// бинарная часть; имена файлов должны быть добавлены в parameters вручную
    /// (см. CreatePostAsync/UploadPostFilesAsync).
    /// </summary>
    public async Task<ApiResult> SendAsync(string controller, string action,
        IReadOnlyList<string>? parameters = null, UploadFile[]? files = null)
    {
        List<string> list = parameters is null ? new List<string>() : new List<string>(parameters);
        Validate(list, files);

        StringBuilder text = new(controller.Length + action.Length + 32);
        text.Append(controller).Append('/').Append(action);
        foreach (string p in list)
        {
            text.Append(Endl).Append(p ?? string.Empty);
        }

        try
        {
            string raw = await ExchangeAsync(text.ToString(), files).ConfigureAwait(false);
            return ApiResult.Parse(raw);
        }
        catch (Exception ex)
        {
            return ApiResult.Fail($"Нет связи с бэкендом {Host}:{Port} ({ex.Message})");
        }
    }

    private async Task<string> ExchangeAsync(string textPart, UploadFile[]? files)
    {
        using TcpClient socket = new();
        socket.NoDelay = true;

        using CancellationTokenSource timeout = new(TimeSpan.FromMinutes(3));
        CancellationToken ct = timeout.Token;

        await socket.ConnectAsync(Host, Port, ct).ConfigureAwait(false);

        await using NetworkStream stream = socket.GetStream();

        byte[] textBytes = Encoding.UTF8.GetBytes(textPart);
        await stream.WriteAsync(textBytes, ct).ConfigureAwait(false);

        await stream.WriteAsync(Encoding.ASCII.GetBytes(BinaryMarker), ct).ConfigureAwait(false);

        if (files is not null)
        {
            foreach (UploadFile file in files)
            {
                byte[] length = new byte[4];
                BinaryPrimitives.WriteInt32BigEndian(length, file.Content.Length);
                await stream.WriteAsync(length, ct).ConfigureAwait(false);
                await stream.WriteAsync(file.Content, ct).ConfigureAwait(false);
            }
        }

        await stream.WriteAsync(Terminator, ct).ConfigureAwait(false);

        using MemoryStream response = new();
        byte[] buffer = new byte[81920];
        int read;
        while ((read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            response.Write(buffer, 0, read);
        }

        return Encoding.UTF8.GetString(response.ToArray());
    }

    private static void Validate(IReadOnlyList<string> parameters, UploadFile[]? files)
    {
        for (int i = 0; i < parameters.Count; i++)
        {
            string p = parameters[i] ?? string.Empty;

            if (p.Contains(Endl))
                throw new ArgumentException("Параметр содержит запрещенную подстроку <endl>");
            if (p.Contains(BinaryMarker))
                throw new ArgumentException("Параметр содержит запрещенную подстроку <BINARY>");
            if (i < parameters.Count - 1 && p.Contains(Security))
                throw new ArgumentException("Подстрока <security> допустима только в последнем параметре (блок авторизации)");
        }

        if (files is null) return;

        foreach (UploadFile f in files)
        {
            if (f.FileName.Contains(Endl) || f.FileName.Contains(BinaryMarker) || f.FileName.Contains(Security))
                throw new ArgumentException($"Недопустимое имя файла: {f.FileName}");
        }
    }

    private static void RequireAuth()
    {
        if (!AppSession.IsAuthenticated)
            throw new InvalidOperationException("Требуется вход в систему");
    }

    private static List<string> Protected(params string[] parameters)
    {
        RequireAuth();

        List<string> ps = new(parameters.Length + 1) { AppSession.SecurityParam };
        for (int i = parameters.Length - 1; i >= 0; i--)
        {
            ps.Insert(0, parameters[i]);
        }

        return ps;
    }

    /// <summary>Защищенный запрос с файлами: параметры, затем имена файлов, затем блок авторизации.</summary>
    private static List<string> ProtectedWithFiles(string[] parameters, UploadFile[]? files)
    {
        RequireAuth();

        List<string> ps = new(parameters);
        AppendFileNames(ps, files);
        ps.Add(AppSession.SecurityParam);

        return ps;
    }

    // ------------------------------------------------------------------
    // AuthController
    // ------------------------------------------------------------------

    public Task<ApiResult> RegisterAsync(string name, string surname, string login, string password) =>
        SendAsync("AuthController", "registerAction", new[] { name, surname, login, password });

    public async Task<ApiResult> LoginAsync(string login, string password)
    {
        ApiResult result = await SendAsync("AuthController", "loginAction",
            new[] { login, password }).ConfigureAwait(false);

        if (result.Success &&
            result.Data is { } data &&
            data.TryGetProperty("user", out JsonElement user))
        {
            AppSession.SignIn(
                GetLong(user, "id"),
                GetString(user, "name"),
                GetString(user, "surname"),
                login,
                password,
                GetString(user, "role"));
        }

        return result;
    }

    public Task<ApiResult> LogoutAsync()
    {
        AppSession.SignOut();
        return Task.FromResult(ApiResult.Ok(JsonSerializer.SerializeToElement(true)));
    }

    // ------------------------------------------------------------------
    // FeedController
    // ------------------------------------------------------------------

    public Task<ApiResult> GetFeedAsync(int page, int size) =>
        SendAsync("FeedController", "getFeedAction", Protected(
            page.ToString(),
            size.ToString()));

    // ------------------------------------------------------------------
    // GroupController
    // ------------------------------------------------------------------

    public Task<ApiResult> GetGroupAsync(long groupId) =>
        SendAsync("GroupController", "getGroupAction", Protected(groupId.ToString()));

    public Task<ApiResult> GetGroupPostsAsync(long groupId, int page, int size, string? search = null)
    {
        List<string> ps = search is { Length: > 0 }
            ? Protected(groupId.ToString(), page.ToString(), size.ToString(), search)
            : Protected(groupId.ToString(), page.ToString(), size.ToString());
        return SendAsync("GroupController", "getGroupPostsAction", ps);
    }

    public Task<ApiResult> SearchGroupsAsync(int page, int size, string? search = null)
    {
        List<string> ps = search is { Length: > 0 }
            ? Protected(page.ToString(), size.ToString(), search)
            : Protected(page.ToString(), size.ToString());
        return SendAsync("GroupController", "searchGroupsAction", ps);
    }

    public Task<ApiResult> GetMyGroupsAsync() =>
        SendAsync("GroupController", "getMyGroupsAction", Protected());

    public Task<ApiResult> GetSubscribedGroupsAsync() =>
        SendAsync("GroupController", "getSubscribedGroupsAction", Protected());

    public Task<ApiResult> CreateGroupAsync(string title, string description) =>
        SendAsync("GroupController", "createGroupAction", Protected(title, description));

    public Task<ApiResult> UpdateGroupAsync(long groupId, string title, string description) =>
        SendAsync("GroupController", "updateGroupAction", Protected(
            groupId.ToString(), title, description));

    public Task<ApiResult> DeleteGroupAsync(long groupId) =>
        SendAsync("GroupController", "deleteGroupAction", Protected(groupId.ToString()));

    public Task<ApiResult> SubscribeAsync(long groupId) =>
        SendAsync("GroupController", "subscribeAction", Protected(groupId.ToString()));

    public Task<ApiResult> UnsubscribeAsync(long groupId) =>
        SendAsync("GroupController", "unsubscribeAction", Protected(groupId.ToString()));

    // ------------------------------------------------------------------
    // PostController
    // ------------------------------------------------------------------

    public Task<ApiResult> GetPostAsync(long postId) =>
        SendAsync("PostController", "getPostAction", Protected(postId.ToString()));

    public Task<ApiResult> UpdatePostAsync(long postId, string title, string body) =>
        SendAsync("PostController", "updatePostAction", Protected(
            postId.ToString(), title, body));

    public Task<ApiResult> DeletePostAsync(long postId) =>
        SendAsync("PostController", "deletePostAction", Protected(postId.ToString()));

    public Task<ApiResult> DeleteAttachmentAsync(long attachmentId) =>
        SendAsync("PostController", "deleteAttachmentAction", Protected(attachmentId.ToString()));

    public Task<ApiResult> DownloadAttachmentAsync(long attachmentId) =>
        SendAsync("PostController", "downloadAttachmentAction", Protected(attachmentId.ToString()));

    /// <summary>Создание поста, опционально с файлами. Бинарная часть формируется автоматически.</summary>
    public Task<ApiResult> CreatePostAsync(long groupId, string title, string body, UploadFile[]? files)
    {
        List<string> ps = ProtectedWithFiles(
            new[] { groupId.ToString(), title, body }, files);

        return SendAsync("PostController", "createPostAction", ps, files);
    }

    /// <summary>Дозагрузка файлов к существующему посту.</summary>
    public Task<ApiResult> UploadPostFilesAsync(long postId, UploadFile[] files)
    {
        List<string> ps = ProtectedWithFiles(new[] { postId.ToString() }, files);

        return SendAsync("PostController", "uploadPostFilesAction", ps, files);
    }

    // ------------------------------------------------------------------
    // CommentController
    // ------------------------------------------------------------------

    public Task<ApiResult> CreateCommentAsync(long postId, string body) =>
        SendAsync("CommentController", "createCommentAction", Protected(postId.ToString(), body));

    public Task<ApiResult> UpdateCommentAsync(long commentId, string body) =>
        SendAsync("CommentController", "updateCommentAction", Protected(commentId.ToString(), body));

    public Task<ApiResult> DeleteCommentAsync(long commentId) =>
        SendAsync("CommentController", "deleteCommentAction", Protected(commentId.ToString()));

    public Task<ApiResult> GetCommentsAsync(long postId, int page, int size) =>
        SendAsync("CommentController", "getCommentsAction", Protected(
            postId.ToString(), page.ToString(), size.ToString()));

    // ------------------------------------------------------------------
    // Хелперы разбора JSON
    // ------------------------------------------------------------------

    internal static void AppendFileNames(List<string> ps, UploadFile[]? files)
    {
        if (files is null) return;

        foreach (UploadFile f in files)
        {
            ps.Add(f.FileName);
        }
    }

    internal static string GetString(JsonElement el, string prop)
    {
        return el.TryGetProperty(prop, out JsonElement v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? ""
            : "";
    }

    internal static long GetLong(JsonElement el, string prop)
    {
        if (!el.TryGetProperty(prop, out JsonElement v))
        {
            return 0;
        }

        if (v.ValueKind == JsonValueKind.Number)
        {
            return v.GetInt64();
        }

        if (v.ValueKind == JsonValueKind.String && long.TryParse(v.GetString(), out long n))
        {
            return n;
        }

        return 0;
    }
}
