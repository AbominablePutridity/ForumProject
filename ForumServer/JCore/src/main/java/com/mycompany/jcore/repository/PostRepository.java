package com.mycompany.jcore.repository;

import com.mycompany.jcore.entities.Post;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import vendor.EntityOrm.DataSerializer;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.Repository;

public class PostRepository extends Repository<Post, Post> {

    private static final String POST_SELECT =
            "SELECT p.id, p.title, p.body, p.createdat AS \"createdAt\", p.usergroupid AS \"groupId\", "
            + "g.title AS \"groupTitle\", p.personid AS \"authorId\", u.login AS \"authorLogin\", "
            + "(SELECT COUNT(*) FROM postcomment c WHERE c.postid = p.id) AS \"commentsCount\", "
            + "(SELECT COUNT(*) FROM postattachment a WHERE a.postid = p.id) AS \"attachmentsCount\" "
            + "FROM post p JOIN usergroup g ON g.id = p.usergroupid JOIN person u ON u.id = p.personid ";

    public PostRepository(Post entityClass) {
        super(entityClass);
    }

    public Long insertReturningId(String title, String body, Long groupId, Long authorId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "INSERT INTO post (title, body, createdat, usergroupid, personid) VALUES (?, ?, NOW(), ?, ?) RETURNING id",
                new Object[]{title, body, groupId, authorId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    public Map<String, Object> findById(long id) throws SQLException {
        ResultSet rs = Entity.executeSQL(POST_SELECT + "WHERE p.id = ? LIMIT 1", new Object[]{id});
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long findAuthorId(long postId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT personid FROM post WHERE id = ? LIMIT 1",
                new Object[]{postId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("personid")).longValue();
    }

    public List<Map<String, Object>> findByGroupPage(long groupId, int limit, int offset) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                POST_SELECT + "WHERE p.usergroupid = ? ORDER BY p.createdat DESC, p.id DESC LIMIT ? OFFSET ?",
                new Object[]{groupId, limit, offset}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }

    public long countByGroup(long groupId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT COUNT(*) AS \"total\" FROM post WHERE usergroupid = ?",
                new Object[]{groupId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("total")).longValue();
    }

    public List<Map<String, Object>> findFeedPage(long personId, int limit, int offset) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                POST_SELECT
                + "JOIN subscription s ON s.usergroupid = p.usergroupid AND s.personid = ? "
                + "ORDER BY p.createdat DESC, p.id DESC LIMIT ? OFFSET ?",
                new Object[]{personId, limit, offset}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }

    public long countFeed(long personId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT COUNT(*) AS \"total\" FROM post p "
                + "JOIN subscription s ON s.usergroupid = p.usergroupid WHERE s.personid = ?",
                new Object[]{personId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("total")).longValue();
    }

    public int update(long id, String title, String body) throws SQLException {
        return Entity.executeUpdate(
                "UPDATE post SET title = ?, body = ? WHERE id = ?",
                new Object[]{title, body, id}
        );
    }

    public int delete(long id) throws SQLException {
        return Entity.executeUpdate("DELETE FROM post WHERE id = ?", new Object[]{id});
    }
}
