package com.mycompany.jcore.service;

import com.mycompany.jcore.repository.PostCommentRepository;
import com.mycompany.jcore.repository.PostRepository;
import java.util.List;
import java.util.Map;

/**
 * Сервис комментариев: создание любым авторизованным пользователем,
 * изменение/удаление только автором, список с пагинацией.
 */
public class CommentService {

    private final PostCommentRepository commentRepository;
    private final PostRepository postRepository;
    private final AuthService authService;

    public CommentService(PostCommentRepository commentRepository,
                          PostRepository postRepository,
                          AuthService authService) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.authService = authService;
    }

    /**
     * Создание комментария к посту. Доступно любому авторизованному
     * пользователю, подписка на группу не требуется.
     *
     * @param login     логин автора комментария
     * @param postIdStr id поста строкой (пост должен существовать)
     * @param body      текст комментария (непустой, до 10000 символов)
     * @return JSON с id созданного комментария
     */
    public String createComment(String login, String postIdStr, String body) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long postId = ParseUtil.parseLong(postIdStr);
            if (postId == null) {
                return JsonUtil.err("postId must be a number");
            }
            if (postRepository.findAuthorId(postId) == null) {
                return JsonUtil.err("post not found");
            }
            if (body == null || body.isBlank()) {
                return JsonUtil.err("comment text is required");
            }
            body = body.trim();
            if (body.length() > 10000) {
                return JsonUtil.err("comment is too long (max 10000)");
            }
            Long id = commentRepository.insertReturningId(postId, userId, body);
            return id == null ? JsonUtil.err("comment was not created") : JsonUtil.ok("comment created", id);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Редактирование комментария. Только автор комментария.
     *
     * @param login         логин пользователя
     * @param commentIdStr  id комментария строкой
     * @param body          новый текст (непустой, до 10000 символов)
     * @return JSON с id обновленного комментария
     */
    public String updateComment(String login, String commentIdStr, String body) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long commentId = ParseUtil.parseLong(commentIdStr);
            if (commentId == null) {
                return JsonUtil.err("commentId must be a number");
            }
            Long authorId = commentRepository.findAuthorId(commentId);
            if (authorId == null) {
                return JsonUtil.err("comment not found");
            }
            if (!authorId.equals(userId)) {
                return JsonUtil.err("only the comment author can edit the comment");
            }
            if (body == null || body.isBlank()) {
                return JsonUtil.err("comment text is required");
            }
            body = body.trim();
            if (body.length() > 10000) {
                return JsonUtil.err("comment is too long (max 10000)");
            }
            commentRepository.update(commentId, body);
            return JsonUtil.ok("comment updated", commentId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Удаление комментария. Только автор комментария.
     *
     * @param login        логин пользователя
     * @param commentIdStr id комментария строкой
     * @return JSON с id удаленного комментария
     */
    public String deleteComment(String login, String commentIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long commentId = ParseUtil.parseLong(commentIdStr);
            if (commentId == null) {
                return JsonUtil.err("commentId must be a number");
            }
            Long authorId = commentRepository.findAuthorId(commentId);
            if (authorId == null) {
                return JsonUtil.err("comment not found");
            }
            if (!authorId.equals(userId)) {
                return JsonUtil.err("only the comment author can delete the comment");
            }
            commentRepository.delete(commentId);
            return JsonUtil.ok("comment deleted", commentId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Комментарии поста с пагинацией (старые сверху — привычная лента обсуждения).
     *
     * @param postIdStr id поста строкой
     * @param pageStr   номер страницы (с 1)
     * @param sizeStr   размер страницы (1–50)
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String getComments(String postIdStr, String pageStr, String sizeStr) {
        try {
            Long postId = ParseUtil.parseLong(postIdStr);
            if (postId == null) {
                return JsonUtil.err("postId must be a number");
            }
            if (postRepository.findAuthorId(postId) == null) {
                return JsonUtil.err("post not found");
            }
            int[] ps = ParseUtil.parsePage(pageStr, sizeStr);
            long total = commentRepository.countByPost(postId);
            List<Map<String, Object>> items = commentRepository.findByPostPage(postId, ps[1], (ps[0] - 1) * ps[1]);
            return JsonUtil.page(ps[0], ps[1], total, JsonUtil.arr(items,
                    "id", "body", "createdAt", "postId", "authorId", "authorLogin"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }
}
