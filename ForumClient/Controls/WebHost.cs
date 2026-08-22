using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Platform;
using Avalonia.Threading;
using ForumClient.Services;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace ForumClient.Controls;

/// <summary>
/// NativeControlHost, встраивающий WebView2 в окно Avalonia.
/// Весь трафик виртуального домена https://jcore.forum раздается
/// собственным перехватчиком WebResourceRequested (важно: при использовании
/// SetVirtualHostNameToFolderMapping это событие для замапленных файлов
/// НЕ срабатывает, поэтому маппинг не используется):
///   остальное            - статика из wwwroot;
///   GET  /media/{id}     - сырые байты вложения (Range поддерживается);
///   POST /upload/{postId}/{fileName} - загрузка файла к посту потоком.
/// </summary>
public class WebHost : NativeControlHost
{
    public const string Domain = "jcore.forum";

    private static readonly Dictionary<string, string> MimeByExtension = new(StringComparer.OrdinalIgnoreCase)
    {
        [".html"] = "text/html; charset=utf-8",
        [".htm"] = "text/html; charset=utf-8",
        [".css"] = "text/css; charset=utf-8",
        [".js"] = "text/javascript; charset=utf-8",
        [".json"] = "application/json; charset=utf-8",
        [".png"] = "image/png",
        [".jpg"] = "image/jpeg",
        [".jpeg"] = "image/jpeg",
        [".gif"] = "image/gif",
        [".webp"] = "image/webp",
        [".svg"] = "image/svg+xml",
        [".ico"] = "image/x-icon",
        [".mp4"] = "video/mp4",
        [".webm"] = "video/webm",
        [".mov"] = "video/quicktime",
        [".mp3"] = "audio/mpeg",
        [".wav"] = "audio/wav",
        [".woff"] = "font/woff",
        [".woff2"] = "font/woff2"
    };

    private WebView2? _webView;
    private CoreWebView2Environment? _environment;
    private string _wwwRoot = "";
    private readonly TaskCompletionSource _ready =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>JSON-сообщения от JS (window.chrome.webview.postMessage).</summary>
    public event EventHandler<string>? MessageFromJs;

    /// <summary>Мост для скачивания/загрузки вложений; назначается MainWindow.</summary>
    public Bridge? MediaBridge { get; set; }

    protected override IPlatformHandle CreateNativeControlCore(IPlatformHandle parent)
    {
        if (!OperatingSystem.IsWindows())
        {
            Log.Write("WebHost: платформа не Windows, WebView2 недоступен");
            return base.CreateNativeControlCore(parent);
        }

        Log.Write("WebHost: создание WinForms WebView2");

        _webView = new WebView2
        {
            Dock = System.Windows.Forms.DockStyle.Fill,
            DefaultBackgroundColor = System.Drawing.Color.White
        };

        _ = InitializeAsync();

        return new PlatformHandle(_webView.Handle, nameof(WebHost));
    }

    protected override void DestroyNativeControlCore(Avalonia.Platform.IPlatformHandle control)
    {
        if (_webView is not null && control is not null && control.Handle == _webView.Handle)
        {
            _webView.Dispose();
            _webView = null;
        }
    }

    private async Task InitializeAsync()
    {
        try
        {
            string userDataFolder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "JCoreForumClient", "WebView2");
            Directory.CreateDirectory(userDataFolder);

            Log.Write($"WebHost: создание среды (userData={userDataFolder})");

            CoreWebView2Environment environment =
                await CoreWebView2Environment.CreateAsync(null, userDataFolder);

            Log.Write("WebHost: среда создана");

            WebView2 webView = _webView!;
            await webView.EnsureCoreWebView2Async(environment);
            _environment = environment;

            Log.Write("WebHost: CoreWebView2 готов");

            CoreWebView2 core = webView.CoreWebView2;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.IsZoomControlEnabled = false;

            _wwwRoot = Path.Combine(AppContext.BaseDirectory, "wwwroot");

            // Один фильтр на весь домен - статика, медиа и аплоады идут через нас.
            core.AddWebResourceRequestedFilter(
                $"https://{Domain}/*", CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += OnWebResourceRequested;

            Log.Write($"WebHost: перехват https://{Domain}/* установлен " +
                      $"(wwwroot={_wwwRoot}, существует={Directory.Exists(_wwwRoot)})");

            core.WebMessageReceived += (_, ea) =>
            {
                Log.WriteShort("WebHost <- JS", ea.WebMessageAsJson);
                MessageFromJs?.Invoke(this, ea.WebMessageAsJson);
            };

            core.NavigationCompleted += (_, ea) =>
                Log.Write($"WebHost: навигация завершена, успех={ea.IsSuccess}, " +
                          $"ошибка={ea.WebErrorStatus}");

            // target="_blank" и window.open в приложении не создают новых окон:
            // открываем ссылку в текущем WebView (иначе - страница ошибки).
            core.NewWindowRequested += (_, ea) =>
            {
                Log.Write($"WebHost: запрос нового окна {ea.Uri} -> навигация в текущем окне");
                ea.Handled = true;
                _webView?.CoreWebView2.Navigate(ea.Uri);
            };

            _ready.TrySetResult();
            Log.Write("WebHost: готов к навигации");
        }
        catch (Exception ex)
        {
            Log.Write("WebHost: ОШИБКА инициализации: " + ex);
            _ready.TrySetException(ex);
        }
    }

    // ------------------------------------------------------------------
    // Перехват всех запросов домена
    // ------------------------------------------------------------------

    private async void OnWebResourceRequested(object? sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        Uri uri = new(e.Request.Uri);
        string path = uri.AbsolutePath;

        if (path.StartsWith("/media/", StringComparison.Ordinal))
        {
            var deferral = e.GetDeferral();
            try
            {
                e.Response = await ServeMediaAsync(path, e.Request.Headers);
            }
            catch (ArgumentException ex)
            {
                Log.Write($"WebHost: некорректный аргумент {path}: {ex.Message}");
                e.Response = JsonResponse(400, "Bad Request", JsonError(ex.Message));
            }
            catch (Exception ex)
            {
                Log.Write($"WebHost: ОШИБКА обработки {path}: {ex}");
                e.Response = JsonResponse(500, "Internal Server Error", JsonError(ex.Message));
            }
            finally
            {
                deferral.Complete();
            }

            return;
        }

        if (path.StartsWith("/upload/", StringComparison.Ordinal))
        {
            var deferral = e.GetDeferral();
            try
            {
                e.Response = await ServeUploadAsync(uri, e.Request.Method, e.Request.Content);
            }
            catch (Exception ex)
            {
                Log.Write($"WebHost: ОШИБКА обработки {path}: {ex}");
                e.Response = JsonResponse(500, "Internal Server Error", JsonError(ex.Message));
            }
            finally
            {
                deferral.Complete();
            }

            return;
        }

        // Статика - отдается синхронно, deferral не нужен.
        e.Response = ServeStatic(path);
    }

    // ------------------------------------------------------------------
    // Статика из wwwroot
    // ------------------------------------------------------------------

    private CoreWebView2WebResourceResponse ServeStatic(string path)
    {
        string rel = Uri.UnescapeDataString(path);

        if (rel.Length == 0 || rel == "/")
        {
            rel = "/index.html";
        }

        string full = Path.GetFullPath(Path.Combine(_wwwRoot, rel.TrimStart('/')));

        bool insideRoot = full.StartsWith(
            Path.GetFullPath(_wwwRoot) + Path.DirectorySeparatorChar,
            StringComparison.OrdinalIgnoreCase);
        bool isIndex = string.Equals(full, Path.GetFullPath(_wwwRoot),
            StringComparison.OrdinalIgnoreCase);

        if ((!insideRoot && !isIndex) || !File.Exists(full))
        {
            return JsonResponse(404, "Not Found", JsonError("Файл не найден: " + rel));
        }

        byte[] bytes = File.ReadAllBytes(full);
        string mime = MimeByExtension.TryGetValue(
            Path.GetExtension(full), out string? m) ? m : "application/octet-stream";

        return BuildResponse(bytes, 200, "OK", new Dictionary<string, string>
        {
            ["Content-Type"] = mime,
            ["Cache-Control"] = "no-cache"
        });
    }

    // ------------------------------------------------------------------
    // Медиа вложений
    // ------------------------------------------------------------------

    /// <summary>Отдача вложения сырыми байтами с поддержкой простых Range-запросов.</summary>
    private async Task<CoreWebView2WebResourceResponse> ServeMediaAsync(
        string path, CoreWebView2HttpRequestHeaders requestHeaders)
    {
        string idPart = path["/media/".Length..];

        if (!long.TryParse(idPart, out long attachmentId))
        {
            Log.Write($"WebHost: /media/ 404 (некорректный id '{idPart}')");
            return JsonResponse(404, "Not Found", JsonError("Некорректный id вложения"));
        }

        AttachmentMedia? media = await (MediaBridge?.GetAttachmentMediaAsync(attachmentId)
            ?? Task.FromResult<AttachmentMedia?>(null));

        if (media is null || media.Data.Length == 0)
        {
            Log.Write($"WebHost: /media/{attachmentId} 404 (вложение не скачалось: " +
                      $"media={media is not null}, bridge={MediaBridge is not null})");
            return JsonResponse(404, "Not Found", JsonError("Вложение не найдено"));
        }

        string? range = null;
        try { range = requestHeaders.GetHeader("Range"); } catch { /* нет заголовка */ }

        (int status, string reason, byte[] body, Dictionary<string, string> headers) =
            ServeBytes(media.Data, media.MimeType, range);

        Log.Write($"WebHost: /media/{attachmentId} -> {status}, {media.MimeType}, " +
                  $"{body.Length} байт{(range is null ? "" : " (Range)")}");

        return BuildResponse(body, status, reason, headers);
    }

    // ------------------------------------------------------------------
    // Загрузка файлов
    // ------------------------------------------------------------------

    /// <summary>Приём загружаемого файла (тело запроса = сырой файл).</summary>
    private async Task<CoreWebView2WebResourceResponse> ServeUploadAsync(
        Uri uri, string method, Stream? content)
    {
        if (!string.Equals(method, "POST", StringComparison.OrdinalIgnoreCase))
        {
            return JsonResponse(405, "Method Not Allowed", JsonError("Только POST"));
        }

        string rest = uri.AbsolutePath["/upload/".Length..];
        int slash = rest.IndexOf('/');

        if (slash <= 0 || slash == rest.Length - 1 ||
            !long.TryParse(rest[..slash], out long postId))
        {
            return JsonResponse(400, "Bad Request",
                JsonError("Ожидается путь /upload/{postId}/{имя файла}"));
        }

        string fileName = Uri.UnescapeDataString(rest[(slash + 1)..]);

        if (content is null)
        {
            return JsonResponse(400, "Bad Request", JsonError("Пустое тело запроса"));
        }

        ApiResult result = await (MediaBridge?.UploadToPostAsync(postId, fileName, content)
            ?? Task.FromResult(ApiResult.Fail("Мост не инициализирован")));

        if (!result.Success)
        {
            Log.Write($"WebHost: загрузка '{fileName}' к посту {postId} отклонена: {result.Error}");
            return JsonResponse(502, "Bad Gateway", JsonError(result.Error));
        }

        Log.Write($"WebHost: файл '{fileName}' загружен к посту {postId}");
        return JsonResponse(200, "OK", "{\"ok\":true}");
    }

    // ------------------------------------------------------------------
    // Вспомогательное формирование ответов
    // ------------------------------------------------------------------

    private static (int Status, string Reason, byte[] Body, Dictionary<string, string> Headers)
        ServeBytes(byte[] data, string contentType, string? rangeHeader)
    {
        Dictionary<string, string> headers = new()
        {
            ["Content-Type"] = contentType,
            ["Accept-Ranges"] = "bytes",
            ["Cache-Control"] = "no-store"
        };

        if (rangeHeader is not null && rangeHeader.StartsWith("bytes=", StringComparison.Ordinal))
        {
            string spec = rangeHeader["bytes=".Length..];
            int dash = spec.IndexOf('-');

            if (dash > 0 &&
                long.TryParse(spec[..dash], out long start) &&
                start >= 0 && start < data.Length)
            {
                long end = dash + 1 < spec.Length && long.TryParse(spec[(dash + 1)..], out long e)
                    ? Math.Min(e, data.Length - 1)
                    : data.Length - 1;

                if (start <= end)
                {
                    int s = (int)start;
                    int en = (int)end;
                    headers["Content-Range"] = $"bytes {s}-{en}/{data.Length}";

                    return (206, "Partial Content", data[s..(en + 1)], headers);
                }
            }
        }

        return (200, "OK", data, headers);
    }

    private CoreWebView2WebResourceResponse BuildResponse(
        byte[] body, int status, string reason, Dictionary<string, string> headers)
    {
        StringBuilder headerText = new();

        foreach ((string key, string value) in headers)
        {
            headerText.Append(key).Append(": ").Append(value).Append("\r\n");
        }

        return _environment!.CreateWebResourceResponse(
            new MemoryStream(body), status, reason, headerText.ToString());
    }

    private CoreWebView2WebResourceResponse JsonResponse(
        int status, string reason, string json) =>
        BuildResponse(Encoding.UTF8.GetBytes(json), status, reason,
            new Dictionary<string, string>
            {
                ["Content-Type"] = "application/json; charset=utf-8",
                ["Cache-Control"] = "no-store"
            });

    private static string JsonError(string message) =>
        System.Text.Json.JsonSerializer.Serialize(new { error = message });

    // ------------------------------------------------------------------

    public async Task NavigateAsync(string pathAndQuery)
    {
        await _ready.Task;

        Log.Write($"WebHost: навигация на https://{Domain}{pathAndQuery}");

        await OnUiAsync(() => _webView!.CoreWebView2.Navigate(
            "https://" + Domain + pathAndQuery));
    }

    public async Task PostMessageToJsAsync(string json)
    {
        await _ready.Task;

        Log.WriteShort("WebHost -> JS", json);

        await OnUiAsync(() => _webView!.CoreWebView2.PostWebMessageAsJson(json));
    }

    private static Task OnUiAsync(Action action) =>
        Dispatcher.UIThread.InvokeAsync(action).GetTask();
}
