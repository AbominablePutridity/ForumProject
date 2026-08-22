using System;
using System.IO;

namespace ForumClient.Services;

/// <summary>
/// Простой файловый лог для диагностики запуска:
/// %LOCALAPPDATA%\JCoreForumClient\client.log
/// </summary>
public static class Log
{
    private static readonly object Gate = new();

    public static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "JCoreForumClient", "client.log");

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
                File.AppendAllText(FilePath,
                    $"[{DateTime.Now:HH:mm:ss.fff}] {message}{Environment.NewLine}");
            }
        }
        catch
        {
            // логирование не должно ломать приложение
        }
    }

    public static void WriteShort(string prefix, string? text, int max = 400)
    {
        if (text is null)
        {
            Write(prefix + ": <null>");
            return;
        }

        text = text.Replace("\n", "\\n").Replace("\r", "");

        if (text.Length > max)
        {
            text = text[..max] + "…";
        }

        Write(prefix + ": " + text);
    }
}
