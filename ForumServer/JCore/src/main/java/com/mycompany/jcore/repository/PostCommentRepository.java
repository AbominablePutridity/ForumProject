package com.mycompany.jcore.repository;

import com.mycompany.jcore.entities.PostComment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import vendor.EntityOrm.DataSerializer;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.Repository;

public class PostCommentRepository extends Repository<PostComment, PostComment> {

    public PostCommentRepository(PostComment entityClass) {
        super(entityClass);
    }

    public Long insertReturningId(Long postId, Long personId, String body) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "INSERT INTO postcomment (body, createdat, postid, personid) VALUES (?, NOW(), ?, ?) RETURNING id",
                new Object[]{body, postId, personId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    public List<Map<String, Object>> findByPostPage(long postId, int limit, int offset) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT c.id, c.body, c.createdat AS \"createdAt\", c.postid AS \"postId\", "
                + "c.personid AS \"authorId\", u.login AS \"authorLogin\" "
                + "FROM postcomment c JOIN person u ON u.id = c.personid "
                + "WHERE c.postid = ? ORDER BY c.createdat ASC, c.id ASC LIMIT ? OFFSET ?",
                new Object[]{postId, limit, offset}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }

    public long countByPost(long postId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT COUNT(*) AS \"total\" FROM postcomment WHERE postid = ?",
                new Object[]{postId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("total")).longValue();
    }

    public Long findAuthorId(long commentId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT personid FROM postcomment WHERE id = ? LIMIT 1",
                new Object[]{commentId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("personid")).longValue();
    }

    public int update(long id, String body) throws SQLException {
        return Entity.executeUpdate(
                "UPDATE postcomment SET body = ? WHERE id = ?",
                new Object[]{body, id}
        );
    }

    public int delete(long id) throws SQLException {
        return Entity.executeUpdate("DELETE FROM postcomment WHERE id = ?", new Object[]{id});
    }
}
