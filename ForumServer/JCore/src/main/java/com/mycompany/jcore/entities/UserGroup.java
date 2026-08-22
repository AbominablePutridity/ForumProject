package com.mycompany.jcore.entities;

import java.sql.Statement;
import java.time.Instant;
import vendor.EntityOrm.Entity;
import vendor.EntityOrm.RelationField;

public class UserGroup extends Entity {
    public String title;
    public String description;
    public Instant createdAt;
    public Long personId;

    public UserGroup(Statement statement) {
        super(statement);
        refs.add(new RelationField(Person.class, personId));
    }
}
