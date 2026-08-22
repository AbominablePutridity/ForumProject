using System.Text.Json;

namespace ForumClient.Services;

/// <summary>
/// Результат одного вызова эндпоинта бэкенда.
/// Успехом считается JSON без status=ERROR; все остальное
/// (ACCESS_DENIED:, ERROR:, пустой ответ) — ошибка.
/// </summary>
public sealed class ApiResult
{
    public bool Success { get; init; }

    public string Raw { get; init; } = "";

    public JsonElement? Data { get; init; }

    public string? Error { get; init; }

    public static ApiResult Ok(JsonElement data) =>
        new() { Success = true, Raw = data.GetRawText(), Data = data };

    public static ApiResult Fail(string error) =>
        new() { Success = false, Raw = error, Error = error };

    public static ApiResult Parse(string raw)
    {
        raw = raw?.Trim() ?? "";

        if (!raw.StartsWith('{'))
        {
            return Fail(raw.Length == 0 ? "Пустой ответ от сервера" : raw);
        }

        try
        {
            using JsonDocument doc = JsonDocument.Parse(raw);
            JsonElement root = doc.RootElement.Clone();

            if (root.TryGetProperty("status", out JsonElement status) &&
                status.ValueKind == JsonValueKind.String &&
                status.GetString() == "ERROR")
            {
                string message = "Ошибка выполнения операции";
                if (root.TryGetProperty("message", out JsonElement msgEl) &&
                    msgEl.ValueKind == JsonValueKind.String)
                {
                    message = msgEl.GetString() ?? message;
                }

                return new ApiResult { Success = false, Raw = raw, Data = root, Error = message };
            }

            return new ApiResult { Success = true, Raw = raw, Data = root };
        }
        catch (JsonException ex)
        {
            return Fail("Некорректный JSON от сервера: " + ex.Message);
        }
    }
}
