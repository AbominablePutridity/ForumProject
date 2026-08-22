package com.mycompany.jcore.service;

import com.mycompany.jcore.repository.PersonRepository;
import java.sql.SQLException;
import java.util.Map;
import vendor.Security.Security;

/**
 * Сервис аутентификации: регистрация, вход и преобразование логина
 * в идентификатор пользователя для остальных сервисов.
 */
public class AuthService {

    private final PersonRepository personRepository;

    public AuthService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    /**
     * Преобразует логин в id пользователя (используется всеми защищенными
     * роутами после checkRole).
     *
     * @param login логин из блока авторизации запроса
     * @return id пользователя либо null, если логин пустой или не найден
     */
    public Long resolveUserId(String login) throws SQLException {
        if (login == null || login.isBlank()) {
            return null;
        }
        return personRepository.findIdByLogin(login.trim());
    }

    /**
     * Регистрация пользователя: валидация полей, уникальность логина,
     * пароль сохраняется как BCrypt-хеш, роль присваивается "USER".
     *
     * @param name     имя (непустое, до 300 символов)
     * @param surname  фамилия (непустая, до 300 символов)
     * @param login    логин (уникальный, до 100 символов)
     * @param password пароль (от 4 до 72 символов)
     * @return JSON со статусом операции
     */
    public String register(String name, String surname, String login, String password) {
        try {
            if (name == null || name.isBlank()) {
                return JsonUtil.err("name is required");
            }
            if (surname == null || surname.isBlank()) {
                return JsonUtil.err("surname is required");
            }
            if (login == null || login.isBlank()) {
                return JsonUtil.err("login is required");
            }
            if (password == null || password.length() < 4) {
                return JsonUtil.err("password must be at least 4 characters");
            }
            name = name.trim();
            surname = surname.trim();
            login = login.trim();
            if (name.length() > 300) {
                return JsonUtil.err("name is too long (max 300)");
            }
            if (surname.length() > 300) {
                return JsonUtil.err("surname is too long (max 300)");
            }
            if (login.length() > 100) {
                return JsonUtil.err("login is too long (max 100)");
            }
            if (password.length() > 72) {
                return JsonUtil.err("password is too long (max 72)");
            }
            if (personRepository.existsByLogin(login)) {
                return JsonUtil.err("login already taken");
            }
            personRepository.insert(name, surname, login, Security.hashPassword(password), "USER");
            return JsonUtil.ok("user registered");
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }

    /**
     * Вход пользователя: проверка логина и сверка пароля с BCrypt-хешем.
     *
     * @param login    логин
     * @param password пароль в открытом виде
     * @return JSON с объектом user (id, name, surname, login, role)
     *         либо ошибка "invalid login or password"
     */
    public String login(String login, String password) {
        try {
            if (login == null || login.isBlank()) {
                return JsonUtil.err("login is required");
            }
            if (password == null || password.isEmpty()) {
                return JsonUtil.err("password is required");
            }
            Map<String, Object> user = personRepository.findByLogin(login.trim());
            if (user == null) {
                return JsonUtil.err("invalid login or password");
            }
            String storedHash = (String) user.get("password");
            if (storedHash == null || !Security.checkHashedPassword(storedHash, password)) {
                return JsonUtil.err("invalid login or password");
            }
            return JsonUtil.okData("user", JsonUtil.obj(user, "id", "name", "surname", "login", "role"));
        } catch (Exception e) {
            return JsonUtil.err(e.getMessage());
        }
    }
}
