package com.mycompany.jcore.service;

import com.mycompany.jcore.repository.SubscriptionRepository;
import com.mycompany.jcore.repository.UserGroupRepository;
import java.util.List;
import java.util.Map;

/**
 * Сервис групп и подписок: CRUD групп с проверкой владельца,
 * каталог, списки "мои группы"/"подписки", подписка/отписка.
 */
public class GroupService {

    private final UserGroupRepository groupRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AuthService authService;
    private final FileStorageService fileStorageService;

    public GroupService(UserGroupRepository groupRepository,
                        SubscriptionRepository subscriptionRepository,
                        AuthService authService,
                        FileStorageService fileStorageService) {
        this.groupRepository = groupRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.authService = authService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Создание группы; автор — текущий пользователь.
     *
     * @param login       логин автора
     * @param title       название (непустое, до 300 символов)
     * @param description описание (необязательное, до 10000 символов)
     * @return JSON с id созданной группы
     */
    public String createGroup(String login, String title, String description) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            if (title == null || title.isBlank()) {
                return JsonUtil.err("title is required");
            }
            title = title.trim();
            if (title.length() > 300) {
                return JsonUtil.err("title is too long (max 300)");
            }
            if (description == null) {
                description = "";
            }
            if (description.length() > 10000) {
                return JsonUtil.err("description is too long (max 10000)");
            }
            Long id = groupRepository.insertReturningId(title, description.trim(), userId);
            return id == null ? JsonUtil.err("group was not created") : JsonUtil.ok("group created", id);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Редактирование группы. Только владелец.
     *
     * @param login       логин пользователя
     * @param groupIdStr  id группы строкой
     * @param title       новое название (непустое, до 300 символов)
     * @param description новое описание (до 10000 символов)
     * @return JSON с id обновленной группы
     */
    public String updateGroup(String login, String groupIdStr, String title, String description) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long groupId = ParseUtil.parseLong(groupIdStr);
            if (groupId == null) {
                return JsonUtil.err("groupId must be a number");
            }
            Long ownerId = groupRepository.findOwnerId(groupId);
            if (ownerId == null) {
                return JsonUtil.err("group not found");
            }
            if (!ownerId.equals(userId)) {
                return JsonUtil.err("only the group owner can edit the group");
            }
            if (title == null || title.isBlank()) {
                return JsonUtil.err("title is required");
            }
            title = title.trim();
            if (title.length() > 300) {
                return JsonUtil.err("title is too long (max 300)");
            }
            if (description == null) {
                description = "";
            }
            if (description.length() > 10000) {
                return JsonUtil.err("description is too long (max 10000)");
            }
            groupRepository.update(groupId, title, description.trim());
            return JsonUtil.ok("group updated", groupId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Удаление группы. Только владелец. Каскадно удаляет посты, комментарии
     * и записи вложений; файлы вложений удаляются с диска.
     *
     * @param login      логин пользователя
     * @param groupIdStr id группы строкой
     * @return JSON с id удаленной группы
     */
    public String deleteGroup(String login, String groupIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long groupId = ParseUtil.parseLong(groupIdStr);
            if (groupId == null) {
                return JsonUtil.err("groupId must be a number");
            }
            Long ownerId = groupRepository.findOwnerId(groupId);
            if (ownerId == null) {
                return JsonUtil.err("group not found");
            }
            if (!ownerId.equals(userId)) {
                return JsonUtil.err("only the group owner can delete the group");
            }
            List<Map<String, Object>> attachments = groupRepository.findAttachmentPathsInGroup(groupId);
            groupRepository.delete(groupId);
            for (Map<String, Object> attachment : attachments) {
                Object path = attachment.get("filePath");
                if (path != null) {
                    fileStorageService.deleteQuietly(path.toString());
                }
            }
            return JsonUtil.ok("group deleted", groupId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Просмотр одной группы: данные + счетчики подписчиков и постов.
     *
     * @param groupIdStr id группы строкой
     * @return JSON с объектом group либо ошибка "group not found"
     */
    public String getGroup(String groupIdStr) {
        try {
            Long groupId = ParseUtil.parseLong(groupIdStr);
            if (groupId == null) {
                return JsonUtil.err("groupId must be a number");
            }
            Map<String, Object> group = groupRepository.findById(groupId);
            if (group == null) {
                return JsonUtil.err("group not found");
            }
            return JsonUtil.okData("group", JsonUtil.obj(group,
                    "id", "title", "description", "createdAt",
                    "ownerId", "ownerLogin", "subscribersCount", "postsCount"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Каталог всех групп с пагинацией (для выбора подписок).
     *
     * @param pageStr   номер страницы (с 1)
     * @param sizeStr   размер страницы (1–50)
     * @param search    строка поиска по названию (null или пусто — без фильтра)
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String searchGroups(String pageStr, String sizeStr, String search) {
        try {
            int[] ps = ParseUtil.parsePage(pageStr, sizeStr);
            long total = groupRepository.countAll(search);
            List<Map<String, Object>> items = groupRepository.findPage(ps[1], (ps[0] - 1) * ps[1], search);
            return JsonUtil.page(ps[0], ps[1], total, JsonUtil.arr(items,
                    "id", "title", "description", "createdAt",
                    "ownerId", "ownerLogin", "subscribersCount", "postsCount"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Группы, созданные пользователем.
     *
     * @param login логин пользователя
     * @return JSON со списком groups
     */
    public String getMyGroups(String login) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            List<Map<String, Object>> items = groupRepository.findByOwner(userId);
            return JsonUtil.data("groups", JsonUtil.arr(items,
                    "id", "title", "description", "createdAt",
                    "ownerId", "ownerLogin", "subscribersCount", "postsCount"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Группы, на которые подписан пользователь.
     *
     * @param login логин пользователя
     * @return JSON со списком groups
     */
    public String getSubscribedGroups(String login) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            List<Map<String, Object>> items = subscriptionRepository.findGroupsByPerson(userId);
            return JsonUtil.data("groups", JsonUtil.arr(items,
                    "id", "title", "description", "createdAt",
                    "ownerId", "ownerLogin", "subscribersCount"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Подписка на группу. Идемпотентно: повторная подписка вернет
     * "already subscribed" без ошибки.
     *
     * @param login      логин пользователя
     * @param groupIdStr id группы строкой
     * @return JSON с id группы
     */
    public String subscribe(String login, String groupIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long groupId = ParseUtil.parseLong(groupIdStr);
            if (groupId == null) {
                return JsonUtil.err("groupId must be a number");
            }
            if (groupRepository.findOwnerId(groupId) == null) {
                return JsonUtil.err("group not found");
            }
            if (subscriptionRepository.exists(userId, groupId)) {
                return JsonUtil.ok("already subscribed", groupId);
            }
            subscriptionRepository.insert(userId, groupId);
            return JsonUtil.ok("subscribed", groupId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Отписка от группы. Идемпотентно: отписка от неподписанной группы
     * тоже вернет OK.
     *
     * @param login      логин пользователя
     * @param groupIdStr id группы строкой
     * @return JSON с id группы
     */
    public String unsubscribe(String login, String groupIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long groupId = ParseUtil.parseLong(groupIdStr);
            if (groupId == null) {
                return JsonUtil.err("groupId must be a number");
            }
            subscriptionRepository.delete(userId, groupId);
            return JsonUtil.ok("unsubscribed", groupId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }
}
