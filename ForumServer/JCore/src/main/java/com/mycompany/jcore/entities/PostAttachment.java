package com.mycompany.jcore.entities;

import java.sql.Statement;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.RelationField;

public class PostAttachment extends Entity {
    public String fileName;
    public String filePath;
    public String mimeType;
    public Long fileSize;
    public Long postId;

    public PostAttachment(Statement statement) {
        super(statement);
        refs.add(new RelationField(Post.class, postId));
    }
}
