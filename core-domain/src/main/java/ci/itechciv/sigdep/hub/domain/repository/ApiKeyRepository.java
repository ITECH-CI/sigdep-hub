package ci.itechciv.sigdep.hub.domain.repository;

import ci.itechciv.sigdep.hub.domain.entity.ApiKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Clés actives partageant un préfixe. Le préfixe seul n'identifie pas la
     * clé de façon unique (collision théorique) : l'appelant compare ensuite
     * le hash BCrypt. En pratique la liste contient 0 ou 1 élément.
     */
    List<ApiKey> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);

    Optional<ApiKey> findBySiteIdAndRevokedAtIsNull(Long siteId);
}
