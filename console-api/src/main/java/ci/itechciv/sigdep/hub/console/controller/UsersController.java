package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.admin.UserAdminService;
import ci.itechciv.sigdep.hub.console.admin.UserAdminService.CreateUserRequest;
import ci.itechciv.sigdep.hub.console.admin.UserAdminService.ResetPasswordRequest;
import ci.itechciv.sigdep.hub.console.admin.UserAdminService.UpdateUserRequest;
import ci.itechciv.sigdep.hub.console.admin.UserAdminService.UserDetail;
import ci.itechciv.sigdep.hub.console.admin.UserAdminService.UserPage;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints d'administration des comptes utilisateurs (auth v2.0, JPA).
 * Remplace l'ancien CRUD adossé à Keycloak.
 *
 * Réservé aux rôles SUPER_ADMIN / IT_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN')")
public class UsersController {

    private final UserAdminService service;

    public UsersController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public UserPage list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/roles")
    public List<String> availableRoles() {
        return service.availableRoles();
    }

    @GetMapping("/{id}")
    public UserDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody CreateUserRequest req) {
        Long id = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable Long id,
                                       @RequestBody UpdateUserRequest req) {
        service.update(id, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.password(), req.temporary());
        return ResponseEntity.noContent().build();
    }

    /** Envoie un lien de réinitialisation à l'utilisateur (l'admin ne saisit rien). */
    @PostMapping("/{id}/send-reset-link")
    public ResponseEntity<Void> sendResetLink(@PathVariable Long id) {
        service.sendResetLink(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        service.setEnabled(id, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        service.setEnabled(id, false);
        return ResponseEntity.noContent().build();
    }

    /** Désactivation (soft) plutôt que suppression : conserve l'historique. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDelete(@PathVariable Long id) {
        service.setEnabled(id, false);
        return ResponseEntity.noContent().build();
    }
}
