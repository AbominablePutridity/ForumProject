package com.mycompany.jcore.controller;

import com.mycompany.jcore.service.JsonUtil;
import com.mycompany.jcore.service.PostService;
import java.sql.Statement;
import vendor.Security.Security;

public class PostController extends Security {

    private static final String AUTH_ERROR = "expected last param: login<security>password";

    private final PostService postService;

    public PostController(Statement statement, PostService postService) {
        super(statement);
        this.postService = postService;
    }

    /**
     * Создание поста с вложениями. Только владелец группы, и только в своей группе.
     * Параметры params:
     * [0] — groupId — идентификатор группы (должна принадлежать вам);
     * [1] — title — заголовок поста (непустой, до 300 символов);
     * [2] — body — текст поста (может быть пустым, до 100000 символов);
     * [3..N-2] — имена файлов вложений (необязательно, по одному параметру на файл);
     * [последний] — блок авторизации "логин<security>пароль".
     * binaryFiles — содержимое файлов в том же порядке, что и их имена;
     * каждый файл до 200 МБ.
     *
     * @param params      параметры запроса
     * @param binaryFiles бинарные данные файлов вложений
     * @return JSON с id поста и числом сохраненных вложений
     */
    public String createPostAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 4) {
            return JsonUtil.err("expected params: groupId, title, body, [fileNames...], " + AUTH_ERROR
                    + " + binary files (order of fileNames matches order of files)");
        }
        String login = extractLoginAndPasswordFromClientQuery(params)[0];
        return postService.createPost(login, params[0], params[1], params[2],
                extractFileNames(params, 3), binaryFiles);
    }

    /**
     * Редактирование поста. Только автор поста.
     * Параметры params:
     * [0] — postId — идентификатор поста;
     * [1] — title — новый заголовок (непустой, до 300 символов);
     * [2] — body — новый текст (до 100000 символов);
     * [3] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id обновленного поста
     */
    public String updatePostAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 4) {
            return JsonUtil.err("expected params: postId, title, body, " + AUTH_ERROR);
        }
        return postService.updatePost(extractLoginAndPasswordFromClientQuery(params)[0], params[0], params[1], params[2]);
    }

    /**
     * Удаление поста. Только автор. Каскадно удаляет комментарии и записи
     * вложений; файлы вложений удаляются с диска.
     * Параметры params:
     * [0] — postId — идентификатор поста;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id удаленного поста
     */
    public String deletePostAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: postId, " + AUTH_ERROR);
        }
        return postService.deletePost(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }

    /**
     * Просмотр одного поста: данные поста + метаданные всех вложений
     * (для отрисовки галереи).
     * Параметры params:
     * [0] — postId — идентификатор поста;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с объектом post и массивом attachments
     */
    public String getPostAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: postId, " + AUTH_ERROR);
        }
        return postService.getPost(params[0]);
    }

    /**
     * Дозагрузка файлов к существующему посту. Только автор поста.
     * Параметры params:
     * [0] — postId — идентификатор поста;
     * [1..N-2] — имена файлов вложений (по одному параметру на файл);
     * [последний] — блок авторизации "логин<security>пароль".
     * binaryFiles — содержимое файлов в том же порядке, что и их имена.
     *
     * @param params      параметры запроса
     * @param binaryFiles бинарные данные файлов вложений
     * @return JSON с id поста и числом сохраненных вложений
     */
    public String uploadPostFilesAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: postId, [fileNames...], " + AUTH_ERROR
                    + " + binary files (order of fileNames matches order of files)");
        }
        String login = extractLoginAndPasswordFromClientQuery(params)[0];
        return postService.uploadAttachments(login, params[0], extractFileNames(params, 1), binaryFiles);
    }

    /**
     * Удаление одного вложения поста (запись из БД + файл с диска).
     * Только автор поста.
     * Параметры params:
     * [0] — attachmentId — идентификатор вложения;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id удаленного вложения
     */
    public String deleteAttachmentAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: attachmentId, " + AUTH_ERROR);
        }
        return postService.deleteAttachment(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }

    /**
     * Скачивание вложения: содержимое файла возвращается в base64
     * (транспорт фреймворка — текстовый протокол).
     * Параметры params:
     * [0] — attachmentId — идентификатор вложения;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с метаданными файла и полем dataBase64
     */
    public String downloadAttachmentAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: attachmentId, " + AUTH_ERROR);
        }
        return postService.downloadAttachment(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }

    private String[] extractFileNames(String[] params, int fromIndex) {
        int end = params.length - 1;
        if (end <= fromIndex) {
            return new String[0];
        }
        String[] names = new String[end - fromIndex];
        System.arraycopy(params, fromIndex, names, 0, names.length);
        return names;
    }
}
