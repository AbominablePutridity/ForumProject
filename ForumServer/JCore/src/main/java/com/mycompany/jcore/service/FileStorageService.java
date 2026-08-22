package com.mycompany.jcore.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * Хранилище файлов вложений на диске (папка uploads относительно
 * рабочей директории сервера). В БД хранятся только метаданные.
 */
public class FileStorageService {

    /** Максимальный размер одного файла: 200 МБ. */
    public static final long MAX_FILE_SIZE = 200L * 1024 * 1024;

    private final Path storageDir;

    public FileStorageService() {
        this.storageDir = Paths.get("uploads");
    }

    /**
     * Сохраняет файл вложения на диск в папку uploads: имя очищается
     * от недопустимых символов, к нему добавляются postId и UUID
     * (защита от коллизий и перезаписи).
     *
     * @param postId       id поста (для префикса имени)
     * @param originalName исходное имя файла
     * @param data         содержимое файла (1 байт – 200 МБ)
     * @return метаданные: fileName, filePath, mimeType, fileSize
     */
    public Map<String, Object> save(long postId, String originalName, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("empty file");
        }
        if (data.length > MAX_FILE_SIZE) {
            throw new IOException("file is too large (max " + MAX_FILE_SIZE + " bytes)");
        }
        Files.createDirectories(storageDir);
        String safeName = sanitize(originalName);
        String storedName = "post" + postId + "_" + UUID.randomUUID() + "_" + safeName;
        Path target = storageDir.resolve(storedName);
        Files.write(target, data);
        return Map.of(
                "fileName", safeName,
                "filePath", target.toString().replace('\\', '/'),
                "mimeType", detectMimeType(safeName),
                "fileSize", (long) data.length
        );
    }

    /**
     * Читает файл с диска целиком (для отдачи в base64).
     */
    public byte[] read(String filePath) throws IOException {
        return Files.readAllBytes(Paths.get(filePath));
    }

    /**
     * Удаляет файл с диска, глуша любые ошибки (используется при
     * каскадных удалениях, чтобы не срывать основную операцию).
     */
    public void deleteQuietly(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (Exception ignored) {
        }
    }

    /**
     * Очищает имя файла от недопустимых символов файловой системы,
     * пустое имя заменяет на "file.bin", обрезает до 120 символов.
     */
    private String sanitize(String originalName) {
        String name = (originalName == null || originalName.isBlank()) ? "file.bin" : originalName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        return name;
    }

    /**
     * Определяет MIME-тип по расширению имени файла; неизвестные
     * расширения дают "application/octet-stream".
     */
    public String detectMimeType(String fileName) {
        String ext = extOf(fileName).toLowerCase();
        switch (ext) {
            case ".jpg", ".jpeg" -> {
                return "image/jpeg";
            }
            case ".png" -> {
                return "image/png";
            }
            case ".gif" -> {
                return "image/gif";
            }
            case ".webp" -> {
                return "image/webp";
            }
            case ".bmp" -> {
                return "image/bmp";
            }
            case ".svg" -> {
                return "image/svg+xml";
            }
            case ".mp4" -> {
                return "video/mp4";
            }
            case ".webm" -> {
                return "video/webm";
            }
            case ".mov" -> {
                return "video/quicktime";
            }
            case ".avi" -> {
                return "video/x-msvideo";
            }
            case ".mkv" -> {
                return "video/x-matroska";
            }
            case ".mp3" -> {
                return "audio/mpeg";
            }
            case ".wav" -> {
                return "audio/wav";
            }
            case ".ogg" -> {
                return "audio/ogg";
            }
            case ".pdf" -> {
                return "application/pdf";
            }
            case ".txt" -> {
                return "text/plain";
            }
            case ".zip" -> {
                return "application/zip";
            }
            default -> {
                return "application/octet-stream";
            }
        }
    }

    /**
     * Возвращает расширение имени файла вместе с точкой ("" если его нет).
     */
    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
