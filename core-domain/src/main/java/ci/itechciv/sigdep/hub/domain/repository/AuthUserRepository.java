package ci.itechciv.sigdep.hub.domain.repository;

import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {

    Optional<AuthUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByActiveTrue();

    /**
     * Recherche par email ou nom. {@code pattern} est un motif LIKE déjà
     * normalisé en minuscules et entouré de {@code %} (jamais null) — fourni
     * par le service. Passer {@code "%"} pour tout renvoyer.
     *
     * NB : on ne réutilise pas un paramètre potentiellement null dans
     * {@code LOWER(...)} pour éviter que PostgreSQL ne l'infère en {@code bytea}
     * ("function lower(bytea) does not exist").
     */
    @Query("""
            SELECT u FROM AuthUser u
            WHERE LOWER(u.email) LIKE :pattern
               OR LOWER(u.displayName) LIKE :pattern
            """)
    Page<AuthUser> search(@Param("pattern") String pattern, Pageable pageable);
}
