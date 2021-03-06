package org.example.dao;

import org.example.exceptions.UserAlreadyExistsException;
import org.example.model.Job;
import org.example.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


@Repository("userDao")
public class UserDaoImpl implements UserDao {

    private final EntityManagerFactory entityManagerFactory;

    @Autowired
    public UserDaoImpl(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public long save(User user) throws UserAlreadyExistsException {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.joinTransaction();

        setJobFromPersistentIfAlreadyExists(user, entityManager);

        long savedUserId = -1;
        if (!userIsAlreadyPersisted(user, entityManager)) {
            entityManager.persist(user);
            savedUserId = user.getId();
        } else {
            throw new UserAlreadyExistsException("Пользователь " + user + " был сохранен в базе данных ранее");
        }

        return savedUserId;
    }

    private boolean userIsAlreadyPersisted(User user, EntityManager entityManager) {
        return entityManager.createQuery("SELECT u FROM User u " +
                "WHERE u.name = :name AND u.surname = :surname AND u.age = :age", User.class)
                .setParameter("name", user.getName())
                .setParameter("surname", user.getSurname())
                .setParameter("age", user.getAge())
                .getResultList()
                .contains(user);
    }

    private void setJobFromPersistentIfAlreadyExists(User user, EntityManager entityManager) {
        user.getJob().flatMap(userJob ->
                entityManager.createQuery("SELECT j FROM Job j " +
                        "WHERE j.name = :name AND j.salary = :salary", Job.class)
                        .setParameter("name", userJob.getName())
                        .setParameter("salary", userJob.getSalary())
                        .getResultStream().findFirst()
        ).ifPresent(user::setJob);
    }

    @Override
    public void delete(long id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.joinTransaction();

        User loadedUser = entityManager.find(User.class, id);

        if (loadedUser != null) {
            entityManager.remove(loadedUser);
            loadedUser.getJob().ifPresent(job -> removeIfOrphanJob(job, entityManager));
        }
    }

    private void removeIfOrphanJob(Job job, EntityManager entityManager) {
        List<User> usersWithTheSameJob = entityManager.createQuery("SELECT u FROM User u " +
                "WHERE u.job = :job", User.class)
                .setParameter("job", job)
                .getResultList();

        if (usersWithTheSameJob.size() == 0) {
            // на эту запись job больше никто не ссылается из таблицы user,
            // ее можно удалять
            entityManager.remove(job);
        }
    }

    @Override
    public List<User> listUsers() {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.joinTransaction();
        return entityManager.createQuery("SELECT u FROM User u LEFT JOIN FETCH u.job", User.class).getResultList();
    }

    @Override
    public User getUserById(long id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.joinTransaction();
        return entityManager.find(User.class, id);
    }

    @Override
    public void update(long id, User user) throws UserAlreadyExistsException {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.joinTransaction();

        User targetUser = entityManager.find(User.class, id);

        if (!targetUser.equals(user)) {
            Optional<Job> oldUserJob = Optional.empty();

            if (!jobsAreTheSame(targetUser.getJob(), user.getJob())) {
                user.getJob().ifPresent(job -> setJobFromPersistentIfAlreadyExists(user, entityManager));
                oldUserJob = targetUser.getJob();
            }

            if (!userIsAlreadyPersisted(user, entityManager)) {
                targetUser.setName(user.getName());
                targetUser.setSurname(user.getSurname());
                targetUser.setAge(user.getAge());
                targetUser.setJob(user.getJob().orElse(null));
            } else {
                throw new UserAlreadyExistsException("Пользователь " + user + " был сохранен в базе данных ранее");
            }

            oldUserJob.ifPresent(oldJob -> removeIfOrphanJob(oldJob, entityManager));
        }
    }

    private boolean jobsAreTheSame(Optional<Job> originalJobOpt, Optional<Job> newJobOpt) {
        return Objects.equals(originalJobOpt.orElse(null), newJobOpt.orElse(null));
    }
}
