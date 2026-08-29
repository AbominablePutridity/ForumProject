using System;
using System.IO;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
using AvaloniaWebView;
using ForumClient.Services;
using WebViewCore.Events;

namespace ForumClient.Controls;

/// <summary>
/// Кроссплатформенное веб-окно поверх WebView.Avalonia (Windows — WebView2,
/// Linux — WebKitGTK, macOS — WKWebView). Страницы грузятся из bundled-папки
/// wwwroot по file://. Вся связь с C# — через JS-мост (не HTTP):
///   JS → C#: window.chrome.webview.postMessage / window.webkit.messageHandlers
///   C# → JS: PostWebMessageAsString (api.js слушает chrome.webview и __dispatchMessageCallback)
/// Медиа и загрузка файлов также идут через мост как base64 (см. Bridge).
/// </summary>
public class WebHost : UserControl
{
    private readonly TaskCompletionSource _ready =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private WebView? _webView;
    private string _wwwRoot = "";

    /// <summary>JSON-сообщения от JS (одна строка JSON).</summary>
    public event EventHandler<string>? MessageFromJs;

    public WebHost()
    {
        Log.Write("WebHost: конструирование");

        _wwwRoot = Path.Combine(AppContext.BaseDirectory, "wwwroot");

        _webView = new WebView
        {
            Background = Avalonia.Media.Brushes.White,
            // Стартовый URL задаём сразу: без него платформенный webview не
            // инициализируется (создание происходит в OnAttachedToVisualTree по
            // событию изменения Url), и WebViewCreated не выстрелил бы.
            Url = BuildFileUri("index.html")
        };

        _webView.WebViewCreated += OnWebViewCreated;
        _webView.NavigationCompleted += OnNavigationCompleted;
        _webView.WebViewNewWindowRequested += OnNewWindowRequested;
        _webView.WebMessageReceived += OnWebMessageReceived;

        Content = _webView;

        Log.Write("WebHost: контроль создан");
    }

    // ------------------------------------------------------------------
    // События платформенного веб-вью
    // ------------------------------------------------------------------

    private void OnWebViewCreated(object? sender, WebViewCreatedEventArgs e)
    {
        Log.Write($"WebHost: WebView создан, успех={e.IsSucceed}" +
                  (string.IsNullOrEmpty(e.Message) ? "" : ", " + e.Message));

        if (!e.IsSucceed)
        {
            _ready.TrySetException(new InvalidOperationException(
                "Не удалось создать веб-движок: " + e.Message));
            return;
        }

        _ready.TrySetResult();
        Log.Write("WebHost: готов к навигации");
    }

    private void OnNavigationCompleted(object? sender, WebViewUrlLoadedEventArg e)
        => Log.Write($"WebHost: навигация завершена, успех={e.IsSuccess}");

    private void OnNewWindowRequested(object? sender, WebViewNewWindowEventArgs e)
    {
        // target="_blank"/window.open в приложении не создают новых окон:
        // открываем URL в текущем webview (иначе — пустая страница).
        Log.Write($"WebHost: запрос нового окна {e.Url} -> навигация в текущем окне");
        e.UrlLoadingStrategy = WebViewCore.Enums.UrlRequestStrategy.OpenInWebView;
    }

    /// <summary>Строка JSON от веб-страницы.</summary>
    private void OnWebMessageReceived(object? sender, WebViewMessageReceivedEventArgs e)
    {
        string json = string.IsNullOrEmpty(e.MessageAsJson) ? e.Message : e.MessageAsJson;
        Log.WriteShort("WebHost <- JS", json);
        MessageFromJs?.Invoke(this, json);
    }

    // ------------------------------------------------------------------
    // Публичный API для MainWindow
    // ------------------------------------------------------------------

    /// <summary>
    /// Переход на страницу оболочки, например "/group.html?id=1".
    /// Относительный путь мапится на файл из wwwroot (file://); query сохраняется.
    /// </summary>
    public async Task NavigateAsync(string pathAndQuery)
    {
        if (_webView is null)
        {
            throw new InvalidOperationException("WebView не инициализирован");
        }

        string query = "";
        string path = pathAndQuery;

        int q = pathAndQuery.IndexOf('?');
        if (q >= 0)
        {
            query = pathAndQuery[q..];
            path = pathAndQuery[..q];
        }

        string rel = path.TrimStart('/');
        if (rel.Length == 0)
        {
            rel = "index.html";
        }

        Uri uri = BuildFileUri(rel, query);

        Log.Write($"WebHost: навигация на '{uri.AbsoluteUri}'");

        await _ready.Task;
        await OnUiAsync(() => _webView.Url = uri);
    }

    /// <summary>Собирает file://-URI на файл из wwwroot (с опциональным query).</summary>
    private Uri BuildFileUri(string rel, string query = "")
    {
        string full = Path.GetFullPath(Path.Combine(_wwwRoot, rel));
        string url = new Uri(full).AbsoluteUri + query;
        return new Uri(url);
    }

    /// <summary>Отправка ответа моста в JS (api.js слушает chrome.webview / __dispatchMessageCallback).</summary>
    public async Task PostMessageToJsAsync(string json)
    {
        await _ready.Task;

        if (_webView is null)
        {
            throw new InvalidOperationException("WebView не инициализирован");
        }

        Log.WriteShort("WebHost -> JS", json);

        await OnUiAsync(() => _webView.PostWebMessageAsString(json, null));
    }

    private static Task OnUiAsync(Action action) =>
        Dispatcher.UIThread.InvokeAsync(action).GetTask();
}