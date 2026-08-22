package vendor.DI;

import com.mycompany.jcore.entities.Person;
import com.mycompany.jcore.entities.Post;
import com.mycompany.jcore.entities.PostAttachment;
import com.mycompany.jcore.entities.PostComment;
import com.mycompany.jcore.entities.Subscription;
import com.mycompany.jcore.entities.UserGroup;
import com.mycompany.jcore.repository.PersonRepository;
import com.mycompany.jcore.repository.PostAttachmentRepository;
import com.mycompany.jcore.repository.PostCommentRepository;
import com.mycompany.jcore.repository.PostRepository;
import com.mycompany.jcore.repository.SubscriptionRepository;
import com.mycompany.jcore.repository.UserGroupRepository;
import com.mycompany.jcore.service.AuthService;
import com.mycompany.jcore.service.CommentService;
import com.mycompany.jcore.service.FeedService;
import com.mycompany.jcore.service.FileStorageService;
import com.mycompany.jcore.service.GroupService;
import com.mycompany.jcore.service.PostService;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import vendor.ControllerComponent.Connection.Server;
import vendor.ControllerComponent.Controller;
import vendor.EntityOrm.ConfigJDBC;

/**
 *  Это конфиг для регистрации бинов в проекте для удобного доступа к зависимостям извне
 * @author User
 */
public class ConfigDI {

    /**
     * Конфиг, для создания и выкладки бинов в контейнер
     *
     * Задаем базовые бины в контейнер (для дальнейшего внедрения).
     * @throws SQLException
     */
    public static void setBeans() throws SQLException
    {
        //регестрируем бин для маршрутизации контроллеров в приложении
        ContainerDI.register(Controller.class, new Controller());

        //регестрируем бин сервера
        ContainerDI.register(Server.class, new Server(ContainerDI.getBean(Controller.class), 8082)); //на порту 8082

        //регестрируем бин для подключения БД
        ContainerDI.register(Connection.class, new ConfigJDBC().getConnectionDB());

        //регестрируем бин Statement для выполнения им SQL запросов в сущностях (для внедрения его в конструктор сущности)
        ContainerDI.register(Statement.class, ContainerDI.getBean(Connection.class).createStatement()); //создается на основе взятия обьекта подключения

        /*
        Ваши бины можете регестрировать здесь (сущности, репозитории и тд)
        (Контроллеры (их обьекты) регестрируются в обьекте бина сервера, в обьекте Controller controllerPull,
        кладутся в List<Object> declaredControllers - это сделанно для роутинга)
        */

        //регестрируем сущности форума
        ContainerDI.register(Person.class, new Person(ContainerDI.getBean(Statement.class)));
        ContainerDI.register(UserGroup.class, new UserGroup(ContainerDI.getBean(Statement.class)));
        ContainerDI.register(Post.class, new Post(ContainerDI.getBean(Statement.class)));
        ContainerDI.register(PostComment.class, new PostComment(ContainerDI.getBean(Statement.class)));
        ContainerDI.register(PostAttachment.class, new PostAttachment(ContainerDI.getBean(Statement.class)));
        ContainerDI.register(Subscription.class, new Subscription(ContainerDI.getBean(Statement.class)));

        //регестрируем репозитории форума
        ContainerDI.register(PersonRepository.class, new PersonRepository(ContainerDI.getBean(Person.class)));
        ContainerDI.register(UserGroupRepository.class, new UserGroupRepository(ContainerDI.getBean(UserGroup.class)));
        ContainerDI.register(PostRepository.class, new PostRepository(ContainerDI.getBean(Post.class)));
        ContainerDI.register(PostCommentRepository.class, new PostCommentRepository(ContainerDI.getBean(PostComment.class)));
        ContainerDI.register(PostAttachmentRepository.class, new PostAttachmentRepository(ContainerDI.getBean(PostAttachment.class)));
        ContainerDI.register(SubscriptionRepository.class, new SubscriptionRepository(ContainerDI.getBean(Subscription.class)));

        //регестрируем сервисы форума
        ContainerDI.register(FileStorageService.class, new FileStorageService());
        ContainerDI.register(AuthService.class, new AuthService(ContainerDI.getBean(PersonRepository.class)));
        ContainerDI.register(GroupService.class, new GroupService(
                ContainerDI.getBean(UserGroupRepository.class),
                ContainerDI.getBean(SubscriptionRepository.class),
                ContainerDI.getBean(AuthService.class),
                ContainerDI.getBean(FileStorageService.class)));
        ContainerDI.register(PostService.class, new PostService(
                ContainerDI.getBean(PostRepository.class),
                ContainerDI.getBean(UserGroupRepository.class),
                ContainerDI.getBean(PostAttachmentRepository.class),
                ContainerDI.getBean(AuthService.class),
                ContainerDI.getBean(FileStorageService.class)));
        ContainerDI.register(CommentService.class, new CommentService(
                ContainerDI.getBean(PostCommentRepository.class),
                ContainerDI.getBean(PostRepository.class),
                ContainerDI.getBean(AuthService.class)));
        ContainerDI.register(FeedService.class, new FeedService(
                ContainerDI.getBean(PostRepository.class),
                ContainerDI.getBean(AuthService.class)));
    }
}
