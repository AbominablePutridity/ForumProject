package com.mycompany.jcore.repository;

import com.mycompany.jcore.entities.Subscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import vendor.EntityOrm.DataSerializer;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.Repository;

public class SubscriptionRepository extends Repository<Subscription, Subscription> {

    public SubscriptionRepository(Subscription entityClass) {
        super(entityClass);
    }

    public int insert(Long personId, Long groupId) throws SQLException {
        return Entity.executeUpdate(
                "INSERT INTO subscription (createdat, personid, usergroupid) VALUES (NOW(), ?, ?)",
                new Object[]{personId, groupId}
        );
    }

    public int delete(Long personId, Long groupId) throws SQLException {
        return Entity.executeUpdate(
                "DELETE FROM subscription WHERE personid = ? AND usergroupid = ?",
                new Object[]{personId, groupId}
        );
    }

    public boolean exists(Long personId, Long groupId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT id FROM subscription WHERE personid = ? AND usergroupid = ? LIMIT 1",
                new Object[]{personId, groupId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return !rows.isEmpty();
    }

    public List<Map<String, Object>> findGroupsByPerson(long personId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT g.id, g.title, g.description, g.createdat AS \"createdAt\", g.personid AS \"ownerId\", "
                + "p.login AS \"ownerLogin\", "
                + "(SELECT COUNT(*) FROM subscription s2 WHERE s2.usergroupid = g.id) AS \"subscribersCount\" "
                + "FROM subscription s JOIN usergroup g ON g.id = s.usergroupid JOIN person p ON p.id = g.personid "
                + "WHERE s.personid = ? ORDER BY g.title",
                new Object[]{personId}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }
}
