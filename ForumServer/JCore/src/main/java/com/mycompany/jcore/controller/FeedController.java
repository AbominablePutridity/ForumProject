package com.mycompany.jcore.controller;

import com.mycompany.jcore.service.FeedService;
import com.mycompany.jcore.service.JsonUtil;
import java.sql.Statement;
import vendor.Security.Security;

public class FeedController extends Security {

    private final FeedService feedService;

    public FeedController(Statement statement, FeedService feedService) {
        super(statement);
        this.feedService = feedService;
    }

    /**
     * Главная лента: последние посты всех групп, на которые подписан пользователь
     * (новые сверху). Требует роль USER.
     * Параметры params:
     * [0] — page — номер страницы (с 1);
     * [1] — size — размер страницы (1–50);
     * [2] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String getFeedAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 3) {
            return JsonUtil.err("expected params: page, size, login<security>password");
        }
        return feedService.getFeed(extractLoginAndPasswordFromClientQuery(params)[0], params[0], params[1]);
    }
}
