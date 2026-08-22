package com.mycompany.jcore.entities;

import java.sql.Statement;
import java.time.Instant;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.RelationField;

public class PostComment extends Entity {
    public String body;
    public Instant createdAt;
    public Long postId;
    public Long personId;

    public PostComment(Statement statement) {
        super(statement);
        refs.add(new RelationField(Post.class, postId));
        refs.add(new RelationField(Person.class, personId));
    }
}
