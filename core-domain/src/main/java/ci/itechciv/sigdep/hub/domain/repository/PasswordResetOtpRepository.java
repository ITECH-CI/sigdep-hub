package ci.itechciv.sigdep.hub.domain.repository;

import ci.itechciv.sigdep.hub.domain.entity.PasswordResetOtp;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {

    /** Dernier token émis pour un utilisateur (le plus récent). */
    Optional<PasswordResetOtp> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Invalide tous les tokens encore actifs d'un utilisateur (en les marquant
     * utilisés) — appelé avant d'en émettre un nouveau, pour qu'un seul lien
     * soit valide à la fois.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PasswordResetOtp t SET t.usedAt = CURRENT_TIMESTAMP "
         + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateActiveForUser(@Param("userId") Long userId);
}
