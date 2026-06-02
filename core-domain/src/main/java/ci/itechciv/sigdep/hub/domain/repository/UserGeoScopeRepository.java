package ci.itechciv.sigdep.hub.domain.repository;

import ci.itechciv.sigdep.hub.domain.entity.UserGeoScope;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGeoScopeRepository extends JpaRepository<UserGeoScope, Long> {

    Optional<UserGeoScope> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
