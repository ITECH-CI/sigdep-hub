package ci.itechciv.sigdep.hub.domain.repository;

import ci.itechciv.sigdep.hub.domain.entity.PasswordResetOtp;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {

    /** Dernier OTP émis pour un utilisateur (le plus récent). */
    Optional<PasswordResetOtp> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
