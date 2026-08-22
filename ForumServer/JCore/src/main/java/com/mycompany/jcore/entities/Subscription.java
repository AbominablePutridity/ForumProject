package com.mycompany.jcore.entities;

import java.sql.Statement;
import java.time.Instant;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.RelationField;

public class Subscription extends Entity {
    public Instant createdAt;
    public Long personId;
    public Long userGroupId;

    public Subscription(Statement statement) {
        super(statement);
        refs.add(new RelationField(Person.class, personId));
        refs.add(new RelationField(UserGroup.class, userGroupId));
    }
}
