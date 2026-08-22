package com.mycompany.jcore.repository;

import com.mycompany.jcore.entities.UserGroup;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import vendor.EntityOrm.DataSerializer;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.Repository;

public class UserGroupRepository extends Repository<UserGroup, UserGroup> {

    private static final String GROUP_SELECT =
            "SELECT g.id, g.title, g.description, g.createdat AS \"createdAt\", g.personid AS \"ownerId\", "
            + "p.login AS \"ownerLogin\", "
            + "(SELECT COUNT(*) FROM subscription s WHERE s.usergroupid = g.id) AS \"subscribersCount\", "
            + "(SELECT COUNT(*) FROM post po WHERE po.usergroupid = g.id) AS \"postsCount\" "
            + "FROM usergroup g JOIN person p ON p.id = g.personid ";

    public UserGroupRepository(UserGroup entityClass) {
        super(entityClass);
    }

    public Long insertReturningId(String title, String description, Long ownerId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "INSERT INTO usergroup (title, description, createdat, personid) VALUES (?, ?, NOW(), ?) RETURNING id",
                new Object[]{title, description, ownerId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    public Map<String, Object> findById(long id) throws SQLException {
        ResultSet rs = Entity.executeSQL(GROUP_SELECT + "WHERE g.id = ? LIMIT 1", new Object[]{id});
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long findOwnerId(long groupId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT personid FROM usergroup WHERE id = ? LIMIT 1",
                new Object[]{groupId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("personid")).longValue();
    }

    public int update(long id, String title, String description) throws SQLException {
        return Entity.executeUpdate(
                "UPDATE usergroup SET title = ?, description = ? WHERE id = ?",
                new Object[]{title, description, id}
        );
    }

    public int delete(long id) throws SQLException {
        return Entity.executeUpdate("DELETE FROM usergroup WHERE id = ?", new Object[]{id});
    }

    public List<Map<String, Object>> findByOwner(long ownerId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                GROUP_SELECT + "WHERE g.personid = ? ORDER BY g.createdat DESC, g.id DESC",
                new Object[]{ownerId}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }

    public List<Map<String, Object>> findPage(int limit, int offset) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                GROUP_SELECT + "ORDER BY g.createdat DESC, g.id DESC LIMIT ? OFFSET ?",
                new Object[]{limit, offset}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }

    public long countAll() throws SQLException {
        ResultSet rs = Entity.executeSQL("SELECT COUNT(*) AS \"total\" FROM usergroup", new Object[]{});
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("total")).longValue();
    }

    public List<Map<String, Object>> findAttachmentPathsInGroup(long groupId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT a.filepath AS \"filePath\" FROM postattachment a "
                + "JOIN post p ON p.id = a.postid WHERE p.usergroupid = ?",
                new Object[]{groupId}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }
}
