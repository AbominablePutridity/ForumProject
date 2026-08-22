package com.mycompany.jcore.repository;

import com.mycompany.jcore.entities.Person;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import vendor.EntityOrm.DataSerializer;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.Repository;

public class PersonRepository extends Repository<Person, Person> {

    public PersonRepository(Person entityClass) {
        super(entityClass);
    }

    public Map<String, Object> findByLogin(String login) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT id, name, surname, login, password, role FROM person WHERE login = ? LIMIT 1",
                new Object[]{login}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsByLogin(String login) throws SQLException {
        return findByLogin(login) != null;
    }

    public Long findIdByLogin(String login) throws SQLException {
        Map<String, Object> row = findByLogin(login);
        return row == null ? null : ((Number) row.get("id")).longValue();
    }

    public int insert(String name, String surname, String login, String passwordHash, String role) throws SQLException {
        return Entity.executeUpdate(
                "INSERT INTO person (name, surname, login, password, role) VALUES (?, ?, ?, ?, ?)",
                new Object[]{name, surname, login, passwordHash, role}
        );
    }
}
