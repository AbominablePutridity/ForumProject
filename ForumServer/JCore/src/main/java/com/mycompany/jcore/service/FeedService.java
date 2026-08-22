package com.mycompany.jcore.service;

import com.mycompany.jcore.repository.PostRepository;
import java.util.List;
import java.util.Map;

/**
 * Сервис главной ленты: посты всех групп, на которые подписан пользователь.
 */
public class FeedService {

    private final PostRepository postRepository;
    private final AuthService authService;

    public FeedService(PostRepository postRepository, AuthService authService) {
        this.postRepository = postRepository;
        this.authService = authService;
    }

    /**
     * Лента подписок с пагинацией, новые посты сверху. Если подписок нет,
     * возвращается пустая страница (total=0).
     *
     * @param login   логин пользователя
     * @param pageStr номер страницы (с 1)
     * @param sizeStr размер страницы (1–50)
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String getFeed(String login, String pageStr, String sizeStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            int[] ps = ParseUtil.parsePage(pageStr, sizeStr);
            long total = postRepository.countFeed(userId);
            List<Map<String, Object>> items = postRepository.findFeedPage(userId, ps[1], (ps[0] - 1) * ps[1]);
            return JsonUtil.page(ps[0], ps[1], total, JsonUtil.arr(items,
                    "id", "title", "body", "createdAt", "groupId", "groupTitle",
                    "authorId", "authorLogin", "commentsCount", "attachmentsCount"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }
}
