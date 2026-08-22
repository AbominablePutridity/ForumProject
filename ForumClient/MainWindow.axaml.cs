using System;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using ForumClient.Controls;
using ForumClient.Services;

namespace ForumClient;

public partial class MainWindow : Window
{
    private readonly Bridge _bridge = new();

    private bool _initialNavigationStarted;

    public MainWindow()
    {
        Log.Write("MainWindow: конструктор начат");

        InitializeComponent();

        Web.MediaBridge = _bridge;
        Web.MessageFromJs += OnJsMessage;

        AttachedToVisualTree += (_, _) => RequestNavigation("AttachedToVisualTree");
        Opened += (_, _) => RequestNavigation("Opened");

        DispatcherTimer.RunOnce(
            () => RequestNavigation("таймер-фолбэк"),
            TimeSpan.FromMilliseconds(1500));

        Log.Write("MainWindow: конструктор завершен");
    }

    /// <summary>Стартовая страница: JCORE_START_PATH или index.html.</summary>
    private static string StartPath =>
        Environment.GetEnvironmentVariable("JCORE_START_PATH") is { Length: > 0 } p
            ? p
            : "/index.html";

    private void RequestNavigation(string reason)
    {
        if (_initialNavigationStarted)
        {
            return;
        }

        _initialNavigationStarted = true;

        Log.Write($"MainWindow: запускаю первую навигацию ({reason}) на {StartPath}");
        _ = NavigateToIndexAsync();
    }

    private async Task NavigateToIndexAsync()
    {
        try
        {
            await Web.NavigateAsync(StartPath);
            Log.Write("MainWindow: NavigateAsync завершился без ошибок");
        }
        catch (Exception ex)
        {
            Log.Write("MainWindow: ОШИБКА навигации: " + ex);
            ShowFatal(ex);
        }
    }

    private async void OnJsMessage(object? sender, string json)
    {
        string response = await _bridge.HandleAsync(json);

        try
        {
            await Web.PostMessageToJsAsync(response);
        }
        catch (Exception ex)
        {
            Log.Write("MainWindow: ошибка PostMessageToJs: " + ex.Message);
        }
    }

    private void ShowFatal(Exception ex)
    {
        FatalText.Text = ex.ToString();
        FatalOverlay.IsVisible = true;
    }
}
