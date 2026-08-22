package com.mycompany.jcore.controller;

import com.mycompany.jcore.service.CommentService;
import com.mycompany.jcore.service.JsonUtil;
import java.sql.Statement;
import vendor.Security.Security;

public class CommentController extends Security {

    private static final String AUTH_ERROR = "expected last param: login<security>password";

    private final CommentService commentService;

    public CommentController(Statement statement, CommentService commentService) {
        super(statement);
        this.commentService = commentService;
    }

    /**
     * Создание комментария к посту. Доступно любому авторизованному пользователю,
     * подписка на группу не требуется.
     * Параметры params:
     * [0] — postId — идентификатор поста (должен существовать);
     * [1] — body — текст комментария (непустой, до 10000 символов);
     * [2] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id созданного комментария
     */
    public String createCommentAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 3) {
            return JsonUtil.err("expected params: postId, body, " + AUTH_ERROR);
        }
        return commentService.createComment(extractLoginAndPasswordFromClientQuery(params)[0], params[0], params[1]);
    }

    /**
     * Редактирование комментария. Только автор комментария.
     * Параметры params:
     * [0] — commentId — идентификатор комментария;
     * [1] — body — новый текст комментария (непустой, до 10000 символов);
     * [2] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id обновленного комментария
     */
    public String updateCommentAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 3) {
            return JsonUtil.err("expected params: commentId, body, " + AUTH_ERROR);
        }
        return commentService.updateComment(extractLoginAndPasswordFromClientQuery(params)[0], params[0], params[1]);
    }

    /**
     * Удаление комментария. Только автор комментария.
     * Параметры params:
     * [0] — commentId — идентификатор комментария;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id удаленного комментария
     */
    public String deleteCommentAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: commentId, " + AUTH_ERROR);
        }
        return commentService.deleteComment(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }

    /**
     * Список комментариев поста с пагинацией (старые сверху).
     * Параметры params:
     * [0] — postId — идентификатор поста;
     * [1] — page — номер страницы (с 1);
     * [2] — size — размер страницы (1–50);
     * [3] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String getCommentsAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 4) {
            return JsonUtil.err("expected params: postId, page, size, " + AUTH_ERROR);
        }
        return commentService.getComments(params[0], params[1], params[2]);
    }
}
