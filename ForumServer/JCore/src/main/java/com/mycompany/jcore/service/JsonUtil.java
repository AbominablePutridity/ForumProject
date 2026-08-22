package com.mycompany.jcore.service;

import java.util.List;
import java.util.Map;

/**
 * Утилиты ручной сборки JSON-ответов без внешних зависимостей:
 * экранирование, объекты/массивы из строк БД, конверты пагинации
 * и стандартные обертки OK/ERROR.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * Экранирует строку по спецификации JSON: кавычки, обратный слеш,
     * управляющие символы (\b \f \n \r \t, остальные — шестнадцатеричным эскейпом).
     */
    public static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Значение JSON: null -> null, Number/Boolean -> как есть,
     * остальное -> строка в кавычках с экранированием.
     */
    public static String val(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + esc(value.toString()) + "\"";
    }

    /**
     * Собирает JSON-объект из строки БД по перечисленным колонкам
     * (порядок колонок = порядок полей в объекте).
     */
    public static String obj(Map<String, Object> row, String... columns) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(columns[i]).append("\":").append(val(row.get(columns[i])));
        }
        return sb.append("}").toString();
    }

    /**
     * Собирает JSON-массив объектов из списка строк БД по одним колонкам.
     */
    public static String arr(List<Map<String, Object>> rows, String... columns) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(obj(rows.get(i), columns));
        }
        return sb.append("]").toString();
    }

    /**
     * Конверт пагинации: {"page":P,"size":S,"total":T,"pages":N,"items":[...]}.
     *
     * @param itemsJson уже собранный JSON-массив элементов страницы
     */
    public static String page(int page, int size, long total, String itemsJson) {
        long pages = size > 0 ? (total + size - 1) / size : 0;
        return "{\"page\":" + page
                + ",\"size\":" + size
                + ",\"total\":" + total
                + ",\"pages\":" + pages
                + ",\"items\":" + itemsJson + "}";
    }

    /**
     * Успешный ответ без данных: {"status":"OK","message":"..."}.
     */
    public static String ok(String message) {
        return "{\"status\":\"OK\",\"message\":\"" + esc(message) + "\"}";
    }

    /**
     * Успешный ответ с id созданной/измененной сущности:
     * {"status":"OK","message":"...","id":N}.
     */
    public static String ok(String message, long id) {
        return "{\"status\":\"OK\",\"message\":\"" + esc(message) + "\",\"id\":" + id + "}";
    }

    /**
     * Успешный ответ с данными: {"status":"OK","<field>":<dataJson>}
     * (псевдоним {@link #data}).
     */
    public static String okData(String field, String dataJson) {
        return data(field, dataJson);
    }

    /**
     * Успешный ответ с произвольным набором полей: пары
     * "имя поля", "готовый JSON-фрагмент" ({"status":"OK","a":...,"b":...}).
     */
    public static String data(String... nameAndJson) {
        StringBuilder sb = new StringBuilder("{\"status\":\"OK\"");
        for (int i = 0; i + 1 < nameAndJson.length; i += 2) {
            sb.append(",\"").append(nameAndJson[i]).append("\":").append(nameAndJson[i + 1]);
        }
        return sb.append("}").toString();
    }

    /**
     * Ответ с ошибкой: {"status":"ERROR","message":"..."}.
     */
    public static String err(String message) {
        return "{\"status\":\"ERROR\",\"message\":\"" + esc(message == null ? "unknown error" : message) + "\"}";
    }
}
