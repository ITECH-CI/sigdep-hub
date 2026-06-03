package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.admin.ApiKeyService;
import ci.itechciv.sigdep.hub.console.admin.ApiKeyService.GeneratedKey;
import ci.itechciv.sigdep.hub.console.admin.ApiKeyService.KeyStatus;
import ci.itechciv.sigdep.hub.console.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestion des clés API par site (auth de l'agent sigdep-sync). Réservé aux
 * administrateurs : eux seuls peuvent générer/révoquer une clé.
 *
 * Le secret en clair n'est renvoyé que par {@code POST .../api-key} (génération),
 * une seule fois — le frontend l'affiche puis l'oublie.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/api-key")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN')")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public KeyStatus status(@PathVariable Long siteId) {
        return service.status(siteId);
    }

    @PostMapping
    public GeneratedKey generate(@PathVariable Long siteId,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return service.generate(siteId, user);
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(@PathVariable Long siteId) {
        service.revoke(siteId);
        return ResponseEntity.noContent().build();
    }
}
