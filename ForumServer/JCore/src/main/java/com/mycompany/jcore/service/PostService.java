package com.mycompany.jcore.service;

import com.mycompany.jcore.repository.PostAttachmentRepository;
import com.mycompany.jcore.repository.PostRepository;
import com.mycompany.jcore.repository.UserGroupRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Сервис постов и вложений: CRUD постов с проверкой автора,
 * загрузка/удаление/скачивание вложений, посты группы с пагинацией.
 */
public class PostService {

    private final PostRepository postRepository;
    private final UserGroupRepository groupRepository;
    private final PostAttachmentRepository attachmentRepository;
    private final AuthService authService;
    private final FileStorageService fileStorageService;

    public PostService(PostRepository postRepository,
                       UserGroupRepository groupRepository,
                       PostAttachmentRepository attachmentRepository,
                       AuthService authService,
                       FileStorageService fileStorageService) {
        this.postRepository = postRepository;
        this.groupRepository = groupRepository;
        this.attachmentRepository = attachmentRepository;
        this.authService = authService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Создание поста с вложениями. Только владелец группы, и только
     * в своей группе. Файлы сохраняются через FileStorageService,
     * метаданные — в таблицу postattachment.
     *
     * @param login     логин автора
     * @param groupIdStr id группы строкой
     * @param title     заголовок (непустой, до 300 символов)
     * @param body      текст (может быть пустым, до 100000 символов)
     * @param fileNames имена файлов (порядок соответствует files)
     * @param files     содержимое файлов (каждый до 200 МБ)
     * @return JSON с id поста и числом сохраненных вложений
     */
    public String createPost(String login, String groupIdStr, String title, String body,
                             String[] fileNames, byte[][] files) {
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
                return JsonUtil.err("posts can be created only in your own group");
            }
            if (title == null || title.isBlank()) {
                return JsonUtil.err("title is required");
            }
            title = title.trim();
            if (title.length() > 300) {
                return JsonUtil.err("title is too long (max 300)");
            }
            if (body == null) {
                body = "";
            }
            if (body.length() > 100000) {
                return JsonUtil.err("body is too long (max 100000)");
            }
            Long postId = postRepository.insertReturningId(title, body, groupId, userId);
            if (postId == null) {
                return JsonUtil.err("post was not created");
            }
            int saved = saveAttachments(postId, fileNames, files);
            return JsonUtil.data("post", "{\"id\":" + postId + ",\"attachmentsSaved\":" + saved + "}");
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Редактирование поста. Только автор.
     *
     * @param login     логин пользователя
     * @param postIdStr id поста строкой
     * @param title     новый заголовок (непустой, до 300 символов)
     * @param body      новый текст (до 100000 символов)
     * @return JSON с id обновленного поста
     */
    public String updatePost(String login, String postIdStr, String title, String body) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long postId = ParseUtil.parseLong(postIdStr);
            if (postId == null) {
                return JsonUtil.err("postId must be a number");
            }
            Long authorId = postRepository.findAuthorId(postId);
            if (authorId == null) {
                return JsonUtil.err("post not found");
            }
            if (!authorId.equals(userId)) {
                return JsonUtil.err("only the post author can edit the post");
            }
            if (title == null || title.isBlank()) {
                return JsonUtil.err("title is required");
            }
            title = title.trim();
            if (title.length() > 300) {
                return JsonUtil.err("title is too long (max 300)");
            }
            if (body == null) {
                body = "";
            }
            if (body.length() > 100000) {
                return JsonUtil.err("body is too long (max 100000)");
            }
            postRepository.update(postId, title, body);
            return JsonUtil.ok("post updated", postId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Удаление поста. Только автор. Каскадно удаляет комментарии и записи
     * вложений; файлы вложений удаляются с диска.
     *
     * @param login     логин пользователя
     * @param postIdStr id поста строкой
     * @return JSON с id удаленного поста
     */
    public String deletePost(String login, String postIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long postId = ParseUtil.parseLong(postIdStr);
            if (postId == null) {
                return JsonUtil.err("postId must be a number");
            }
            Long authorId = postRepository.findAuthorId(postId);
            if (authorId == null) {
                return JsonUtil.err("post not found");
            }
            if (!authorId.equals(userId)) {
                return JsonUtil.err("only the post author can delete the post");
            }
            List<Map<String, Object>> attachments = attachmentRepository.findByPost(postId);
            postRepository.delete(postId);
            for (Map<String, Object> attachment : attachments) {
                Object path = attachment.get("filePath");
                if (path != null) {
                    fileStorageService.deleteQuietly(path.toString());
                }
            }
            return JsonUtil.ok("post deleted", postId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Дозагрузка файлов к существующему посту. Только автор поста.
     *
     * @param login     логин пользователя
     * @param postIdStr id поста строкой
     * @param fileNames имена файлов (порядок соответствует files)
     * @param files     содержимое файлов
     * @return JSON с id поста и числом сохраненных вложений
     */
    public String uploadAttachments(String login, String postIdStr, String[] fileNames, byte[][] files) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long postId = ParseUtil.parseLong(postIdStr);
            if (postId == null) {
                return JsonUtil.err("postId must be a number");
            }
            Long authorId = postRepository.findAuthorId(postId);
            if (authorId == null) {
                return JsonUtil.err("post not found");
            }
            if (!authorId.equals(userId)) {
                return JsonUtil.err("only the post author can attach files");
            }
            if (files == null || files.length == 0) {
                return JsonUtil.err("no files sent");
            }
            int saved = saveAttachments(postId, fileNames, files);
            return JsonUtil.ok("attachments saved: " + saved, postId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Удаление одного вложения (запись из БД + файл с диска).
     * Только автор поста, к которому относится вложение.
     *
     * @param login            логин пользователя
     * @param attachmentIdStr  id вложения строкой
     * @return JSON с id удаленного вложения
     */
    public String deleteAttachment(String login, String attachmentIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long attachmentId = ParseUtil.parseLong(attachmentIdStr);
            if (attachmentId == null) {
                return JsonUtil.err("attachmentId must be a number");
            }
            Long authorId = attachmentRepository.findPostAuthorId(attachmentId);
            if (authorId == null) {
                return JsonUtil.err("attachment not found");
            }
            if (!authorId.equals(userId)) {
                return JsonUtil.err("only the post author can delete attachments");
            }
            Map<String, Object> attachment = attachmentRepository.findById(attachmentId);
            attachmentRepository.delete(attachmentId);
            if (attachment != null && attachment.get("filePath") != null) {
                fileStorageService.deleteQuietly(attachment.get("filePath").toString());
            }
            return JsonUtil.ok("attachment deleted", attachmentId);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Просмотр одного поста: данные поста + метаданные всех вложений
     * (для отрисовки галереи).
     *
     * @param postIdStr id поста строкой
     * @return JSON с объектом post и массивом attachments
     */
    public String getPost(String postIdStr) {
        try {
            Long postId = ParseUtil.parseLong(postIdStr);
            if (postId == null) {
                return JsonUtil.err("postId must be a number");
            }
            Map<String, Object> post = postRepository.findById(postId);
            if (post == null) {
                return JsonUtil.err("post not found");
            }
            List<Map<String, Object>> attachments = attachmentRepository.findByPost(postId);
            String postJson = JsonUtil.obj(post,
                    "id", "title", "body", "createdAt", "groupId", "groupTitle",
                    "authorId", "authorLogin", "commentsCount", "attachmentsCount");
            String attachmentsJson = JsonUtil.arr(attachments, "id", "postId", "fileName", "mimeType", "fileSize");
            return JsonUtil.data("post", postJson, "attachments", attachmentsJson);
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Посты одной группы с пагинацией (новые сверху), с счетчиками
     * комментариев и вложений для каждого поста.
     *
     * @param groupIdStr id группы строкой
     * @param pageStr    номер страницы (с 1)
     * @param sizeStr    размер страницы (1–50)
     * @param search     строка поиска по заголовку/тексту (null или пусто — без фильтра)
     * @return JSON-конверт пагинации {page, size, total, pages, items}
     */
    public String getGroupPosts(String groupIdStr, String pageStr, String sizeStr, String search) {
        try {
            Long groupId = ParseUtil.parseLong(groupIdStr);
            if (groupId == null) {
                return JsonUtil.err("groupId must be a number");
            }
            if (groupRepository.findOwnerId(groupId) == null) {
                return JsonUtil.err("group not found");
            }
            int[] ps = ParseUtil.parsePage(pageStr, sizeStr);
            long total = postRepository.countByGroup(groupId, search);
            List<Map<String, Object>> items = postRepository.findByGroupPage(groupId, ps[1], (ps[0] - 1) * ps[1], search);
            return JsonUtil.page(ps[0], ps[1], total, JsonUtil.arr(items,
                    "id", "title", "body", "createdAt", "groupId", "groupTitle",
                    "authorId", "authorLogin", "commentsCount", "attachmentsCount"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Скачивание вложения: файл читается с диска и возвращается в base64
     * (транспорт фреймворка — текстовый протокол).
     *
     * @param login            логин пользователя
     * @param attachmentIdStr  id вложения строкой
     * @return JSON с метаданными файла и полем dataBase64
     */
    public String downloadAttachment(String login, String attachmentIdStr) {
        try {
            Long userId = authService.resolveUserId(login);
            if (userId == null) {
                return JsonUtil.err("user not found");
            }
            Long attachmentId = ParseUtil.parseLong(attachmentIdStr);
            if (attachmentId == null) {
                return JsonUtil.err("attachmentId must be a number");
            }
            Map<String, Object> attachment = attachmentRepository.findById(attachmentId);
            if (attachment == null) {
                return JsonUtil.err("attachment not found");
            }
            byte[] data;
            try {
                data = fileStorageService.read(attachment.get("filePath").toString());
            } catch (IOException e) {
                return JsonUtil.err("file is missing on the server");
            }
            String base64 = Base64.getEncoder().encodeToString(data);
            return JsonUtil.data(
                    "id", JsonUtil.val(attachment.get("id")),
                    "postId", JsonUtil.val(attachment.get("postId")),
                    "fileName", JsonUtil.val(attachment.get("fileName")),
                    "mimeType", JsonUtil.val(attachment.get("mimeType")),
                    "fileSize", JsonUtil.val(attachment.get("fileSize")),
                    "dataBase64", "\"" + base64 + "\"");
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Сохраняет каждый непустой файл на диск и добавляет запись в БД;
     * файл без имени получает имя "fileN.bin".
     *
     * @return число фактически сохраненных вложений
     */
    private int saveAttachments(Long postId, String[] fileNames, byte[][] files)
            throws IOException, SQLException {
        int saved = 0;
        for (int i = 0; i < files.length; i++) {
            byte[] data = files[i];
            if (data == null || data.length == 0) {
                continue;
            }
            String name = (fileNames != null && i < fileNames.length
                    && fileNames[i] != null && !fileNames[i].isBlank())
                    ? fileNames[i]
                    : "file" + (i + 1) + ".bin";
            Map<String, Object> meta = fileStorageService.save(postId, name, data);
            attachmentRepository.insert(postId,
                    (String) meta.get("fileName"),
                    (String) meta.get("filePath"),
                    (String) meta.get("mimeType"),
                    (Long) meta.get("fileSize"));
            saved++;
        }
        return saved;
    }
}
