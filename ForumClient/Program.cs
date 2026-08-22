using System;
using Avalonia;

namespace ForumClient;

internal static class Program
{
    [STAThread]
    public static void Main(string[] args)
    {
        Services.Log.Write("=== Запуск ForumClient, args: " + string.Join(" ", args) + " ===");

        if (args.Length > 0 && args[0] == "--selftest")
        {
            Environment.Exit(SelfTest.RunAll());
            return;
        }

        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .LogToTrace();
}
