using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using ForumClient.Services;

namespace ForumClient;

/// <summary>
/// Автопроверка TCP-клиента без запуска GUI и Java-бэкенда:
/// поднимается локальный мок-сервер, который разбирает запросы
/// по тем же правилам, что vendor.ControllerComponent.Connection.Server.
/// </summary>
internal static class SelfTest
{
    private sealed record MockRequest(string Route, string[] Params, byte[][] Files);

    public static int RunAll()
    {
        Console.OutputEncoding = Encoding.UTF8;

        int failures = 0;

        RunCase(ref failures, "Текстовый запрос + UTF-8", TestTextRoundTrip);
        RunCase(ref failures, "Бинарная обертка (2 файла)", TestBinaryFraming);
        RunCase(ref failures, "Валидация запрещенных подстрок", TestValidation);
        RunCase(ref failures, "Разбор ответов (OK/ERROR/ACCESS_DENIED)", TestResponseParsing);
        RunCase(ref failures, "Блок авторизации последним параметром", TestAuthBlockPlacement);
        RunCase(ref failures, "Мост: сообщение в виде строки", TestBridgeWrappedString);

        if (failures == 0)
        {
            Console.WriteLine("SELFTEST: все проверки пройдены");
            return 0;
        }

        Console.WriteLine($"SELFTEST: провалено проверок — {failures}");
        return 1;
    }

    private static void RunCase(ref int failures, string title, Func<Task> test)
    {
        try
        {
            test().GetAwaiter().GetResult();
            Console.WriteLine($"PASS  {title}");
        }
        catch (Exception ex)
        {
            failures++;
            Console.WriteLine($"FAIL  {title}: {ex.Message}");
        }
    }

    // ------------------------------------------------------------------

    private static TestServer StartServer()
    {
        TestServer server = new();
        server.Start();
        return server;
    }

    private sealed class TestServer : IDisposable
    {
        private TcpListener _listener = null!;

        public int Port { get; private set; }

        public List<MockRequest> Received { get; } = new();

        public string ResponseJson { get; set; } =
            "{\"status\":\"OK\",\"message\":\"mock\"}";

        public void Start()
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;

            _ = Task.Run(AcceptLoopAsync);
        }

        public void Dispose() => _listener.Stop();

        private async Task AcceptLoopAsync()
        {
            while (true)
            {
                TcpClient client;
                try
                {
                    client = await _listener.AcceptTcpClientAsync();
                }
                catch
                {
                    return;
                }

                try
                {
                    Handle(client);
                }
                catch
                {
                    // ignore malformed requests in mock server
                }
                finally
                {
                    client.Dispose();
                }
            }
        }

        private void Handle(TcpClient client)
        {
            using NetworkStream stream = client.GetStream();

            MemoryStream textBuffer = new();
            const string marker = "<BINARY>";
            int markerIndex = 0;
            bool markerFound = false;

            while (true)
            {
                int b = stream.ReadByte();

                if (b == -1) break;

                textBuffer.WriteByte((byte)b);

                if (b == marker[markerIndex])
                {
                    markerIndex++;
                    if (markerIndex == marker.Length)
                    {
                        markerFound = true;
                        break;
                    }
                }
                else
                {
                    markerIndex = 0;
                }
            }

            string text = Encoding.UTF8.GetString(textBuffer.ToArray());

            if (markerFound)
            {
                text = text[..^marker.Length];
            }

            string[] parts = text.Split(new[] { "<endl>" }, StringSplitOptions.None);
            string route = parts[0];
            string[] ps = new string[parts.Length - 1];
            Array.Copy(parts, 1, ps, 0, ps.Length);

            List<byte[]> files = new();

            if (markerFound)
            {
                while (true)
                {
                    byte[] sizeBytes = ReadExactly(stream, 4);
                    int size = BinaryPrimitives.ReadInt32BigEndian(sizeBytes);

                    if (size == 0) break;
                    if (size < 0) throw new IOException("bad size");

                    files.Add(ReadExactly(stream, size));
                }
            }

            lock (Received)
            {
                Received.Add(new MockRequest(route, ps, files.ToArray()));
            }

            byte[] response = Encoding.UTF8.GetBytes(ResponseJson);
            stream.Write(response, 0, response.Length);
            stream.Flush();
        }

        private static byte[] ReadExactly(NetworkStream s, int count)
        {
            byte[] data = new byte[count];
            int offset = 0;

            while (offset < count)
            {
                int read = s.Read(data, offset, count - offset);
                if (read <= 0) throw new IOException("unexpected EOF");
                offset += read;
            }

            return data;
        }
    }

    // ------------------------------------------------------------------

    private static async Task TestTextRoundTrip()
    {
        using TestServer server = StartServer();

        JCoreApiClient api = new("127.0.0.1", server.Port);

        ApiResult result = await api.SendAsync("AuthController", "registerAction",
            new[] { "Ivan", "Иванов", "tuser1", "pass1234" });

        if (!result.Success) throw new Exception("запрос не прошел: " + result.Error);

        lock (server.Received)
        {
            MockRequest req = AssertOne(server);

            Check(req.Route == "AuthController/registerAction", "route неверный: " + req.Route);
            Check(req.Params.Length == 4, "число параметров неверное");
            Check(req.Params[2] == "tuser1", "логин не совпал");
            Check(req.Params[1] == "Иванов", "UTF-8 кириллица искажена");
            Check(req.Files.Length == 0, "файлов быть не должно");
        }
    }

    private static async Task TestBinaryFraming()
    {
        using TestServer server = StartServer();

        JCoreApiClient api = new("127.0.0.1", server.Port);

        byte[] fileA = Encoding.ASCII.GetBytes("HELLO-FILE-A-0123456789");
        byte[] fileB = Encoding.UTF8.GetBytes("Привет, файл Б!");

        AppSession.SignIn(1, "Ivan", "Ivanov", "tuser1", "pass1234", "USER");

        ApiResult result = await api.CreatePostAsync(
            groupId: 7,
            title: "Пост с медиа",
            body: "Тело поста",
            new[]
            {
                new UploadFile("photo.jpg", fileA),
                new UploadFile("клип.mp4", fileB)
            });

        if (!result.Success) throw new Exception("запрос не прошел: " + result.Error);

        lock (server.Received)
        {
            MockRequest req = AssertOne(server);

            Check(req.Route == "PostController/createPostAction", "route неверный: " + req.Route);

            Check(req.Params.Length == 6, $"ожидалось 6 параметров, пришло {req.Params.Length}");
            Check(req.Params[0] == "7", "groupId потерялся");
            Check(req.Params[3] == "photo.jpg", "имя первого файла потерялось");
            Check(req.Params[4] == "клип.mp4", "имя второго файла потерялось");
            Check(req.Params[5].Contains("<security>"), "блок авторизации не в конце");

            Check(req.Files.Length == 2, "до сервера дошли не все файлы");
            Check(req.Files[0].AsSpan().SequenceEqual(fileA), "содержимое файла A исказилось");
            Check(req.Files[1].AsSpan().SequenceEqual(fileB), "содержимое файла B исказилось");
        }
    }

    private static Task TestValidation()
    {
        JCoreApiClient api = new("127.0.0.1", 1);

        Check(Throws(() => api.SendAsync("C", "a", new[] { "x<endl>y" }).GetAwaiter().GetResult()),
            "<endl> в параметре должен отклоняться");

        Check(Throws(() => api.SendAsync("C", "a", new[] { "x<BINARY>" }).GetAwaiter().GetResult()),
            "<BINARY> в параметре должен отклоняться");

        Check(Throws(() => api.SendAsync("C", "a", new[] { "a<security>b", "z" }).GetAwaiter().GetResult()),
            "<security> вне последнего параметра должен отклоняться");

        return Task.CompletedTask;
    }

    private static Task TestResponseParsing()
    {
        ApiResult ok = ApiResult.Parse("{\"status\":\"OK\",\"id\":5}");
        Check(ok.Success && ok.Data.HasValue && ok.Data.Value.GetProperty("id").GetInt32() == 5,
            "успешный JSON должен парситься как Success");

        ApiResult err = ApiResult.Parse("{\"status\":\"ERROR\",\"message\":\"login already taken\"}");
        Check(!err.Success && err.Error == "login already taken", "ERROR JSON должен давать ошибку с message");

        ApiResult denied = ApiResult.Parse("ACCESS_DENIED: ОШИБКА!");
        Check(!denied.Success && denied.Error!.StartsWith("ACCESS_DENIED"), "plain-text ACCESS_DENIED должен быть ошибкой");

        ApiResult empty = ApiResult.Parse("");
        Check(!empty.Success, "пустой ответ должен быть ошибкой");

        return Task.CompletedTask;
    }

    private static async Task TestAuthBlockPlacement()
    {
        using TestServer server = StartServer();

        JCoreApiClient api = new("127.0.0.1", server.Port);
        AppSession.SignIn(9, "N", "S", "login1", "pass1", "USER");

        await api.GetFeedAsync(page: 3, size: 10);
        await api.SubscribeAsync(groupId: 42);
        await api.GetCommentsAsync(postId: 11, page: 2, size: 20);

        lock (server.Received)
        {
            Check(server.Received.Count == 3, "должно быть 3 запроса");

            MockRequest feed = server.Received[0];
            Check(feed.Route == "FeedController/getFeedAction", "feed route");
            Check(feed.Params is ["3", "10", _], "feed параметры: " + string.Join("|", feed.Params));
            Check(feed.Params[^1] == "login1<security>pass1", "feed security block");

            MockRequest sub = server.Received[1];
            Check(sub.Params is ["42", _], "subscribe параметры: " + string.Join("|", sub.Params));

            MockRequest comments = server.Received[2];
            Check(comments.Route == "CommentController/getCommentsAction", "comments route");
            Check(comments.Params is ["11", "2", "20", _], "comments параметры: " + string.Join("|", comments.Params));
        }
    }

    /// <summary>
    /// WebView2 оборачивает строку из postMessage в JSON еще раз.
    /// Мост обязан распознать такое сообщение и обработать его нормально.
    /// </summary>
    private static Task TestBridgeWrappedString()
    {
        Bridge bridge = new();

        string plain = "{\"reqId\":7,\"action\":\"session.get\",\"args\":{}}";
        string wrappedAsJsonString = JsonSerializer.Serialize(plain);

        string response = bridge.HandleAsync(wrappedAsJsonString).GetAwaiter().GetResult();

        Check(response.Contains("\"reqId\":7"),
            "reqId потерялся при разборе обернутой строки: " + response);
        Check(response.Contains("\"ok\":true"),
            "session.get без сессии должен вернуть ok=true, ответ: " + response);

        string direct = bridge.HandleAsync(
            JsonSerializer.Serialize(new { reqId = 8, action = "session.get", args = (object?)new { } }))
            .GetAwaiter().GetResult();

        Check(direct.Contains("\"reqId\":8"), "обычное объектное сообщение тоже должно работать");

        return Task.CompletedTask;
    }

    // ------------------------------------------------------------------

    private static MockRequest AssertOne(TestServer server)
    {
        Check(server.Received.Count == 1,
            $"ожидается один запрос, получено {server.Received.Count}");

        return server.Received[0];
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }

    private static bool Throws(Func<ApiResult> action)
    {
        try
        {
            action();
            return false;
        }
        catch (ArgumentException)
        {
            return true;
        }
        catch (AggregateException ae) when (ae.InnerException is ArgumentException)
        {
            return true;
        }
    }
}
