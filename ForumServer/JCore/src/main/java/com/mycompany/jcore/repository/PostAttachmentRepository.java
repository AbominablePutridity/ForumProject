package com.mycompany.jcore.repository;

import com.mycompany.jcore.entities.PostAttachment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import vendor.EntityOrm.DataSerializer;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.Repository;

public class PostAttachmentRepository extends Repository<PostAttachment, PostAttachment> {

    private static final String ATTACHMENT_SELECT =
            "SELECT a.id, a.postid AS \"postId\", a.filename AS \"fileName\", a.filepath AS \"filePath\", "
            + "a.mimetype AS \"mimeType\", a.filesize AS \"fileSize\" FROM postattachment a ";

    public PostAttachmentRepository(PostAttachment entityClass) {
        super(entityClass);
    }

    public int insert(Long postId, String fileName, String filePath, String mimeType, Long fileSize) throws SQLException {
        return Entity.executeUpdate(
                "INSERT INTO postattachment (filename, filepath, mimetype, filesize, postid) VALUES (?, ?, ?, ?, ?)",
                new Object[]{fileName, filePath, mimeType, fileSize, postId}
        );
    }

    public List<Map<String, Object>> findByPost(long postId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                ATTACHMENT_SELECT + "WHERE a.postid = ? ORDER BY a.id",
                new Object[]{postId}
        );
        return DataSerializer.serializeFromResultDataToList(rs);
    }

    public Map<String, Object> findById(long attachmentId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                ATTACHMENT_SELECT + "WHERE a.id = ? LIMIT 1",
                new Object[]{attachmentId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long findPostAuthorId(long attachmentId) throws SQLException {
        ResultSet rs = Entity.executeSQL(
                "SELECT p.personid AS \"authorId\" FROM postattachment a "
                + "JOIN post p ON p.id = a.postid WHERE a.id = ? LIMIT 1",
                new Object[]{attachmentId}
        );
        List<Map<String, Object>> rows = DataSerializer.serializeFromResultDataToList(rs);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("authorId")).longValue();
    }

    public int delete(long id) throws SQLException {
        return Entity.executeUpdate("DELETE FROM postattachment WHERE id = ?", new Object[]{id});
    }
}
