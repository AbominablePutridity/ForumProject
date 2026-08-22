package com.mycompany.jcore.service;

/**
 * Утилиты разбора строковых параметров запроса в числа.
 */
public final class ParseUtil {

    private ParseUtil() {
    }

    /**
     * Безопасно разбирает строку в Long: null/пустая/нечисловая дают null.
     */
    public static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Разбирает параметры пагинации с значениями по умолчанию и защитой
     * от некорректных чисел: page >= 1 (по умолчанию 1), size 1–50 (по умолчанию 10).
     *
     * @param pageStr строка номера страницы
     * @param sizeStr строка размера страницы
     * @return массив {page, size}
     */
    public static int[] parsePage(String pageStr, String sizeStr) {
        int page = 1;
        int size = 10;
        Long p = parseLong(pageStr);
        Long s = parseLong(sizeStr);
        if (p != null && p > 0) {
            page = (int) Math.min(p, 1_000_000L);
        }
        if (s != null && s > 0) {
            size = (int) Math.min(s, 50L);
        }
        return new int[]{page, size};
    }
}
