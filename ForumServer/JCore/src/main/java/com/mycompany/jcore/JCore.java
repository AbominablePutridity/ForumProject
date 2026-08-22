package com.mycompany.jcore;

import com.mycompany.jcore.controller.AuthController;
import com.mycompany.jcore.controller.CommentController;
import com.mycompany.jcore.controller.FeedController;
import com.mycompany.jcore.controller.GroupController;
import com.mycompany.jcore.controller.PostController;
import com.mycompany.jcore.repository.PersonRepository;
import com.mycompany.jcore.repository.PostAttachmentRepository;
import com.mycompany.jcore.repository.PostCommentRepository;
import com.mycompany.jcore.repository.PostRepository;
import com.mycompany.jcore.repository.SubscriptionRepository;
import com.mycompany.jcore.repository.UserGroupRepository;
import com.mycompany.jcore.service.AuthService;
import com.mycompany.jcore.service.CommentService;
import com.mycompany.jcore.service.FeedService;
import com.mycompany.jcore.service.GroupService;
import com.mycompany.jcore.service.PostService;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;
import vendor.ControllerComponent.Connection.Server;
import vendor.DI.ConfigDI;
import vendor.DI.ContainerDI;
import vendor.EntityOrm.Entity;

/**
 *
 * @author maxim
 */
public class JCore {

    public static void main(String[] args) throws SQLException, IllegalArgumentException, IllegalAccessException, IOException, Exception {
        //инициализация бинов в контейнере
        ConfigDI.setBeans();

        /*
        создаем таблицы в правильном порядке на основе сущностей (нужно делать при старе программы)
        (от справочников к связующим таблицам) - ВАЖНО!!!
        путем вызова метода init() в репозиторных бинах наших сущностей.
        */
        ContainerDI.getBean(PersonRepository.class).init();
        ContainerDI.getBean(UserGroupRepository.class).init();
        ContainerDI.getBean(PostRepository.class).init();
        ContainerDI.getBean(PostCommentRepository.class).init();
        ContainerDI.getBean(PostAttachmentRepository.class).init();
        ContainerDI.getBean(SubscriptionRepository.class).init();

        migrateTextColumns();
        createSubscriptionUniqueIndex();

        //запуск сервера
        Server server = ContainerDI.getBean(Server.class); //берем бин сервера из DI-контейнера

        // регестрируем все наши контроллеры на сервере (для роутинга)
        server.controllerPull.declaredControllers.add(new AuthController(
                ContainerDI.getBean(Statement.class),
                ContainerDI.getBean(AuthService.class)));
        server.controllerPull.declaredControllers.add(new GroupController(
                ContainerDI.getBean(Statement.class),
                ContainerDI.getBean(GroupService.class),
                ContainerDI.getBean(PostService.class)));
        server.controllerPull.declaredControllers.add(new PostController(
                ContainerDI.getBean(Statement.class),
                ContainerDI.getBean(PostService.class)));
        server.controllerPull.declaredControllers.add(new CommentController(
                ContainerDI.getBean(Statement.class),
                ContainerDI.getBean(CommentService.class)));
        server.controllerPull.declaredControllers.add(new FeedController(
                ContainerDI.getBean(Statement.class),
                ContainerDI.getBean(FeedService.class)));

        server.startServer(); //запускаем сервер

        /*
        Настройки для PuTTY (по умолчанию, для тестирования роутов):
        1) Host name (or IP address) - 127.0.0.1 Port - 8082 - по умолчанию
        2) Connection type - Other -> Raw
        3) Close window on exit - Never
        */
    }

    private static void migrateTextColumns() throws SQLException {
        Entity.executeUpdate("ALTER TABLE post ALTER COLUMN body TYPE TEXT", new Object[]{});
        Entity.executeUpdate("ALTER TABLE postcomment ALTER COLUMN body TYPE TEXT", new Object[]{});
        Entity.executeUpdate("ALTER TABLE usergroup ALTER COLUMN description TYPE TEXT", new Object[]{});
    }

    private static void createSubscriptionUniqueIndex() {
        try {
            Entity.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS ux_subscription_person_group ON subscription (personid, usergroupid)",
                    new Object[]{});
        } catch (SQLException ignored) {
        }
    }
}
