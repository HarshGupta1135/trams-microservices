package com.trams.user.infrastructure.persistence;

import com.trams.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** @param email must already be normalised via {@link User#normaliseEmail(String)} */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Paged listing for administrators.
     *
     * <p>Paging is mandatory rather than optional: an unbounded "list all users" endpoint
     * is a denial-of-service vector against the service's own memory as the table grows.
     */
    @Override
    Page<User> findAll(Pageable pageable);
}
