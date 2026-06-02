package ci.itechciv.sigdep.hub.console.auth;

import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.repository.AuthUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crée le compte SUPER_ADMIN initial au premier boot, uniquement si la table
 * {@code auth.users} est vide ET que {@code SIGDEP_ADMIN_EMAIL} /
 * {@code SIGDEP_ADMIN_PASSWORD} sont fournis. Une fois au moins un compte
 * présent, ce runner ne fait plus rien (idempotent).
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final AuthUserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public AdminSeeder(AuthUserRepository users,
                       PasswordEncoder encoder,
                       @Value("${app.admin.email:}") String adminEmail,
                       @Value("${app.admin.password:}") String adminPassword,
                       @Value("${app.admin.display-name:Administrateur SIGDEP}") String adminName) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return; // au moins un compte existe déjà
        }
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.warn("auth.users est vide mais SIGDEP_ADMIN_EMAIL/SIGDEP_ADMIN_PASSWORD "
                    + "ne sont pas définis — aucun compte SUPER_ADMIN seedé. "
                    + "Aucune connexion possible tant qu'un compte n'est pas créé.");
            return;
        }

        AuthUser admin = new AuthUser();
        admin.setEmail(adminEmail.trim());
        admin.setPasswordHash(encoder.encode(adminPassword));
        admin.setDisplayName(adminName);
        admin.setRole("SUPER_ADMIN");
        admin.setUserLevel("NATIONAL");
        admin.setActive(true);
        admin.setPasswordExpired(false);
        users.save(admin);

        log.info("Compte SUPER_ADMIN initial créé pour {}", admin.getEmail());
    }
}
