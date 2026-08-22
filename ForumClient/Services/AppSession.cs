namespace ForumClient.Services;

/// <summary>
/// Текущая сессия пользователя. Логин/пароль держатся в памяти процесса
/// и автоматически подставляются в защищенные запросы.
/// </summary>
public static class AppSession
{
    public static long UserId { get; private set; }

    public static string Name { get; private set; } = "";

    public static string Surname { get; private set; } = "";

    public static string Login { get; private set; } = "";

    public static string Password { get; private set; } = "";

    public static string Role { get; private set; } = "";

    public static bool IsAuthenticated => Login.Length > 0;

    /// <summary>Блок авторизации для защищенных роутов: логин&lt;security&gt;пароль.</summary>
    public static string SecurityParam => $"{Login}<security>{Password}";

    public static void SignIn(long userId, string name, string surname,
        string login, string password, string role)
    {
        UserId = userId;
        Name = name ?? "";
        Surname = surname ?? "";
        Login = login ?? "";
        Password = password ?? "";
        Role = role ?? "USER";
    }

    public static void SignOut()
    {
        UserId = 0;
        Name = "";
        Surname = "";
        Login = "";
        Password = "";
        Role = "";
    }
}
