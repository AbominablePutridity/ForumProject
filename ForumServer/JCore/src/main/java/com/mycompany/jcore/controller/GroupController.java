package com.mycompany.jcore.controller;

import com.mycompany.jcore.service.GroupService;
import com.mycompany.jcore.service.JsonUtil;
import com.mycompany.jcore.service.PostService;
import java.sql.Statement;
import vendor.Security.Security;

public class GroupController extends Security {

    private static final String AUTH_ERROR = "expected last param: login<security>password";

    private final GroupService groupService;
    private final PostService postService;

    public GroupController(Statement statement, GroupService groupService, PostService postService) {
        super(statement);
        this.groupService = groupService;
        this.postService = postService;
    }

    /**
     * Создание группы. Автор группы — текущий пользователь.
     * Параметры params:
     * [0] — title — название группы (непустое, до 300 символов);
     * [1] — description — описание (необязательное, до 10000 символов);
     * [2] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id созданной группы
     */
    public String createGroupAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 3) {
            return JsonUtil.err("expected params: title, description, " + AUTH_ERROR);
        }
        return groupService.createGroup(extractLoginAndPasswordFromClientQuery(params)[0], params[0], params[1]);
    }

    /**
     * Редактирование группы. Только владелец.
     * Параметры params:
     * [0] — groupId — идентификатор группы;
     * [1] — title — новое название (непустое, до 300 символов);
     * [2] — description — новое описание (до 10000 символов);
     * [3] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id обновленной группы
     */
    public String updateGroupAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 4) {
            return JsonUtil.err("expected params: groupId, title, description, " + AUTH_ERROR);
        }
        return groupService.updateGroup(extractLoginAndPasswordFromClientQuery(params)[0], params[0], params[1], params[2]);
    }

    /**
     * Удаление группы. Только владелец. Каскадно удаляет посты, комментарии
     * и записи вложений; файлы вложений удаляются с диска.
     * Параметры params:
     * [0] — groupId — идентификатор группы;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id удаленной группы
     */
    public String deleteGroupAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: groupId, " + AUTH_ERROR);
        }
        return groupService.deleteGroup(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }

    /**
     * Просмотр одной группы: данные + счетчики подписчиков и постов.
     * Параметры params:
     * [0] — groupId — идентификатор группы;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с объектом group
     */
    public String getGroupAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: groupId, " + AUTH_ERROR);
        }
        return groupService.getGroup(params[0]);
    }

    /**
     * Посты одной группы с пагинацией (новые сверху), с счетчиками
     * комментариев и вложений для каждого поста.
     * Параметры params:
     * [0] — groupId — идентификатор группы;
     * [1] — page — номер страницы (с 1);
     * [2] — size — размер страницы (1–50);
     * [3] — (опционально) search — строка поиска по заголовку/тексту поста;
     * [последний] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String getGroupPostsAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 4) {
            return JsonUtil.err("expected params: groupId, page, size, " + AUTH_ERROR);
        }
        String search = (params.length > 4 && !params[3].contains("<security>")) ? params[3] : null;
        return postService.getGroupPosts(params[0], params[1], params[2], search);
    }

    /**
     * Каталог всех групп с пагинацией (для выбора подписок).
     * Параметры params:
     * [0] — page — номер страницы (с 1);
     * [1] — size — размер страницы (1–50);
     * [2] — (опционально) search — строка поиска по названию группы;
     * [последний] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String searchGroupsAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 3) {
            return JsonUtil.err("expected params: page, size, " + AUTH_ERROR);
        }
        String search = (params.length > 3 && !params[2].contains("<security>")) ? params[2] : null;
        return groupService.searchGroups(params[0], params[1], search);
    }

    /**
     * Группы, созданные текущим пользователем.
     * Параметры params:
     * [0] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON со списком groups
     */
    public String getMyGroupsAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        return groupService.getMyGroups(extractLoginAndPasswordFromClientQuery(params)[0]);
    }

    /**
     * Группы, на которые подписан текущий пользователь.
     * Параметры params:
     * [0] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON со списком groups
     */
    public String getSubscribedGroupsAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        return groupService.getSubscribedGroups(extractLoginAndPasswordFromClientQuery(params)[0]);
    }

    /**
     * Подписка на группу (идемпотентно: повторная подписка вернет OK).
     * Параметры params:
     * [0] — groupId — идентификатор группы;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id группы
     */
    public String subscribeAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: groupId, " + AUTH_ERROR);
        }
        return groupService.subscribe(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }

    /**
     * Отписка от группы (идемпотентно).
     * Параметры params:
     * [0] — groupId — идентификатор группы;
     * [1] — блок авторизации "логин<security>пароль".
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с id группы
     */
    public String unsubscribeAction(String[] params, byte[][] binaryFiles) {
        if (!super.checkRole("Person", "login", "password", "role", "USER", params)) {
            return super.returnException();
        }
        if (params.length < 2) {
            return JsonUtil.err("expected params: groupId, " + AUTH_ERROR);
        }
        return groupService.unsubscribe(extractLoginAndPasswordFromClientQuery(params)[0], params[0]);
    }
}
