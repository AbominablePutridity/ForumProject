package com.mycompany.jcore.controller;

import com.mycompany.jcore.service.AuthService;
import java.sql.Statement;
import vendor.Security.Security;

public class AuthController extends Security {

    private final AuthService authService;

    public AuthController(Statement statement, AuthService authService) {
        super(statement);
        this.authService = authService;
    }

    /**
     * Регистрация нового пользователя. Публичный роут (авторизация не нужна).
     * Параметры params:
     * [0] — name — имя (непустое, до 300 символов);
     * [1] — surname — фамилия (непустая, до 300 символов);
     * [2] — login — логин (уникальный, до 100 символов);
     * [3] — password — пароль (от 4 до 72 символов).
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON со статусом операции
     */
    public String registerAction(String[] params, byte[][] binaryFiles) {
        if (params.length < 4) {
            return "{\"status\":\"ERROR\",\"message\":\"expected params: name, surname, login, password\"}";
        }
        return authService.register(params[0], params[1], params[2], params[3]);
    }

    /**
     * Вход пользователя. Публичный роут (авторизация не нужна).
     * Параметры params:
     * [0] — login — логин;
     * [1] — password — пароль (в открытом виде, сверяется с BCrypt-хешем).
     *
     * @param params      параметры запроса
     * @param binaryFiles не используется
     * @return JSON с объектом user (id, name, surname, login, role)
     */
    public String loginAction(String[] params, byte[][] binaryFiles) {
        if (params.length < 2) {
            return "{\"status\":\"ERROR\",\"message\":\"expected params: login, password\"}";
        }
        return authService.login(params[0], params[1]);
    }
}
