package com.mycompany.jcore.entities;

import java.sql.Statement;
import java.time.Instant;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.RelationField;

public class Post extends Entity {
    public String title;
    public String body;
    public Instant createdAt;
    public Long userGroupId;
    public Long personId;

    public Post(Statement statement) {
        super(statement);
        refs.add(new RelationField(UserGroup.class, userGroupId));
        refs.add(new RelationField(Person.class, personId));
    }
}
