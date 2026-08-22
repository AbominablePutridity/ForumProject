package com.mycompany.jcore.client;

import java.io.DataOutputStream;
import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Тестер API форума: отправляет запросы ко всем эндпоинтам сервера JCore
 * и печатает ответы. Файлы для загрузки кладутся в папку client_files.
 * Запуск: java -cp target\classes com.mycompany.jcore.client.ApiTester [host] [port]
 */
public class ApiTester {

    // Папка с тестовыми файлами (находится в корне проекта JCore).
    // Положите туда свои фото/видео и замените имена ниже на свои:
    private static final String FILES_DIR = "client_files";
    private static final String FILE_PHOTO = "photo.jpg";   // <-- замените имя файла
    private static final String FILE_VIDEO = "video.mp4";   // <-- замените имя файла

    private final String host;
    private final int port;

    public ApiTester(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Универсальный метод: отправляет запрос на сервер и возвращает ответ.
     * Формирует текстовую часть "route<endl>param1<endl>...", добавляет
     * бинарную часть <BINARY>[размер][байты]...[0] (даже без файлов —
     * так сервер сразу начинает обработку, не дожидаясь закрытия потока).
     *
     * @param route  роут в формате "ИмяКонтроллера/имяЭкшена"
     * @param params текстовые параметры по порядку (для защищенных роутов
     *               последний — блок "логин<security>пароль")
     * @param files  файлы для загрузки (порядок соответствует именам в params)
     * @return ответ сервера одной строкой (JSON либо ACCESS_DENIED/ERROR)
     */
    public String send(String route, String[] params, File... files) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            StringBuilder text = new StringBuilder(route);
            for (String p : params) {
                text.append("<endl>").append(p);
            }
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            out.write("<BINARY>".getBytes(StandardCharsets.UTF_8));
            for (File f : files) {
                byte[] data = Files.readAllBytes(f.toPath());
                out.writeInt(data.length);
                out.write(data);
            }
            out.writeInt(0);
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Собирает блок авторизации для защищенных роутов.
     *
     * @return строка вида "логин<security>пароль"
     */
    private static String sec(String login, String password) {
        return login + "<security>" + password;
    }

    /**
     * Извлекает первый встреченный "id":N из JSON-ответа —
     * используется для цепочек вызовов (создал группу -> получил id).
     *
     * @return id строкой либо null, если не найден
     */
    public static String extractId(String json) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Находит id вложения по имени файла в ответе getPostAction
     * (структура: {"id":N, ..., "fileName":"имя", ...}).
     *
     * @return id вложения строкой либо null, если файл не найден
     */
    public static String attachmentIdByFileName(String json, String fileName) {
        int f = json.indexOf("\"fileName\":\"" + fileName + "\"");
        if (f < 0) {
            return null;
        }
        int i = json.lastIndexOf("\"id\":", f);
        if (i < 0) {
            return null;
        }
        int s = i + 5;
        int e = s;
        while (e < json.length() && Character.isDigit(json.charAt(e))) {
            e++;
        }
        return json.substring(s, e);
    }

    /**
     * Печатает результат шага; длинные ответы обрезаются до 250 символов
     * с указанием полной длины.
     */
    private static void p(String label, String resp) {
        String shown = resp.length() <= 250 ? resp : resp.substring(0, 250) + "... [" + resp.length() + " chars]";
        System.out.println(label + " -> " + shown);
    }

    /**
     * Сценарий проверки всех эндпоинтов: два пользователя, группа,
     * посты с файлами и без, подписки, лента, комментарии, скачивание
     * вложения и проверки запретов прав. Аргументы: [host] [port].
     */
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8082;
        ApiTester api = new ApiTester(host, port);

        String stamp = Long.toString(System.currentTimeMillis());
        String login1 = "user1_" + stamp;
        String login2 = "user2_" + stamp;
        String pass1 = "pass1234";
        String pass2 = "pass5678";

        File photo = new File(FILES_DIR, FILE_PHOTO);
        File video = new File(FILES_DIR, FILE_VIDEO);
        boolean hasFiles = photo.isFile() && video.isFile();
        if (!hasFiles) {
            System.out.println("(!) В " + FILES_DIR + " нет файлов " + FILE_PHOTO + " и/или " + FILE_VIDEO
                    + " — шаги с загрузкой файлов будут пропущены.");
        }

        p("1. register u1", api.send("AuthController/registerAction",
                new String[]{"Ivan", "Ivanov", login1, pass1}));

        p("2. register duplicate", api.send("AuthController/registerAction",
                new String[]{"Ivan", "Ivanov", login1, pass1}));

        p("3. login wrong password", api.send("AuthController/loginAction",
                new String[]{login1, "wrongpass"}));

        p("4. login u1", api.send("AuthController/loginAction",
                new String[]{login1, pass1}));

        p("5. register u2", api.send("AuthController/registerAction",
                new String[]{"Petra", "Petrova", login2, pass2}));

        String groupResp = api.send("GroupController/createGroupAction",
                new String[]{"Test Group " + stamp, "Тестовая группа", sec(login1, pass1)});
        p("6. createGroup", groupResp);
        String groupId = extractId(groupResp);

        p("7. updateGroup by owner", api.send("GroupController/updateGroupAction",
                new String[]{groupId, "Renamed Group", "Новое описание", sec(login1, pass1)}));

        p("8. updateGroup by stranger (denied)", api.send("GroupController/updateGroupAction",
                new String[]{groupId, "Hack", "Hack", sec(login2, pass2)}));

        String post1Resp = api.send("PostController/createPostAction",
                new String[]{groupId, "First post", "Привет, мир!", sec(login1, pass1)});
        p("9. createPost without files", post1Resp);
        String postId1 = extractId(post1Resp);

        String postId2 = null;
        String mediaResp = null;
        if (hasFiles) {
            mediaResp = api.send("PostController/createPostAction",
                    new String[]{groupId, "Media post", "Смотрите что нашел", FILE_PHOTO, FILE_VIDEO, sec(login1, pass1)},
                    photo, video);
            p("10. createPost with 2 files", mediaResp);
            postId2 = extractId(mediaResp);
        }

        p("11. createPost in foreign group (denied)", api.send("PostController/createPostAction",
                new String[]{groupId, "X", "X", sec(login2, pass2)}));

        p("12. subscribe u1", api.send("GroupController/subscribeAction",
                new String[]{groupId, sec(login1, pass1)}));

        p("13. subscribe u2", api.send("GroupController/subscribeAction",
                new String[]{groupId, sec(login2, pass2)}));

        p("14. getFeed u1", api.send("FeedController/getFeedAction",
                new String[]{"1", "10", sec(login1, pass1)}));

        p("15. getGroupPosts page 1 size 1", api.send("GroupController/getGroupPostsAction",
                new String[]{groupId, "1", "1", sec(login1, pass1)}));

        p("16. getGroup", api.send("GroupController/getGroupAction",
                new String[]{groupId, sec(login1, pass1)}));

        p("17. searchGroups", api.send("GroupController/searchGroupsAction",
                new String[]{"1", "50", sec(login1, pass1)}));

        p("18. getMyGroups", api.send("GroupController/getMyGroupsAction",
                new String[]{sec(login1, pass1)}));

        p("19. getSubscribedGroups u2", api.send("GroupController/getSubscribedGroupsAction",
                new String[]{sec(login2, pass2)}));

        if (postId2 != null) {
            String postResp = api.send("PostController/getPostAction",
                    new String[]{postId2, sec(login1, pass1)});
            p("20. getPost with attachments", postResp);

            String attId = attachmentIdByFileName(postResp, FILE_PHOTO);

            p("21. downloadAttachment", api.send("PostController/downloadAttachmentAction",
                    new String[]{attId, sec(login1, pass1)}));

            p("22. deleteAttachment by stranger (denied)", api.send("PostController/deleteAttachmentAction",
                    new String[]{attId, sec(login2, pass2)}));

            p("23. deleteAttachment by author", api.send("PostController/deleteAttachmentAction",
                    new String[]{attId, sec(login1, pass1)}));

            p("24. uploadPostFiles (+1 file)", api.send("PostController/uploadPostFilesAction",
                    new String[]{postId2, "extra_" + FILE_PHOTO, sec(login1, pass1)}, photo));

            String cResp = api.send("CommentController/createCommentAction",
                    new String[]{postId2, "Отличный пост!", sec(login2, pass2)});
            p("25. createComment by u2", cResp);
            String commentId = extractId(cResp);

            p("26. updateComment by stranger (denied)", api.send("CommentController/updateCommentAction",
                    new String[]{commentId, "Hacked", sec(login1, pass1)}));

            p("27. updateComment by author", api.send("CommentController/updateCommentAction",
                    new String[]{commentId, "Исправлено автором", sec(login2, pass2)}));

            p("28. getComments", api.send("CommentController/getCommentsAction",
                    new String[]{postId2, "1", "10", sec(login1, pass1)}));

            p("29. deleteComment by author", api.send("CommentController/deleteCommentAction",
                    new String[]{commentId, sec(login2, pass2)}));
        }

        p("30. unsubscribe u2", api.send("GroupController/unsubscribeAction",
                new String[]{groupId, sec(login2, pass2)}));

        p("31. getFeed u2 after unsubscribe (empty)", api.send("FeedController/getFeedAction",
                new String[]{"1", "10", sec(login2, pass2)}));

        p("32. unauthenticated (denied)", api.send("FeedController/getFeedAction",
                new String[]{"1", "10", sec("ghost_" + stamp, "nopass")}));

        if (postId2 != null) {
            p("33. updatePost by author", api.send("PostController/updatePostAction",
                    new String[]{postId2, "Обновленный заголовок", "Обновленный текст", sec(login1, pass1)}));

            p("34. deletePost by author", api.send("PostController/deletePostAction",
                    new String[]{postId2, sec(login1, pass1)}));

            p("35. getPost after delete", api.send("PostController/getPostAction",
                    new String[]{postId2, sec(login1, pass1)}));
        } else {
            p("33. updatePost by author", api.send("PostController/updatePostAction",
                    new String[]{postId1, "Обновленный заголовок", "Обновленный текст", sec(login1, pass1)}));

            p("34. deletePost by author", api.send("PostController/deletePostAction",
                    new String[]{postId1, sec(login1, pass1)}));
        }

        p("36. deleteGroup by owner", api.send("GroupController/deleteGroupAction",
                new String[]{groupId, sec(login1, pass1)}));

        p("37. getGroup after delete", api.send("GroupController/getGroupAction",
                new String[]{groupId, sec(login1, pass1)}));
    }
}
