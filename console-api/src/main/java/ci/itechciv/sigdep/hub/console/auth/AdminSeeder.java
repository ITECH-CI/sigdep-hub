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
 * Crée les comptes d'administration initiaux au démarrage : un SUPER_ADMIN et
 * un IT_ADMIN. Le seed est piloté par la config ({@code app.admin.*} /
 * {@code app.it-admin.*}) — en prod, fournir les valeurs via le {@code .env} ;
 * en dev, des défauts permettent d'avoir l'appli opérationnelle sans config.
 *
 * Le seed est <b>idempotent par email</b> : chaque compte n'est créé que s'il
 * n'existe pas déjà. Modifier le mot de passe d'un compte existant se fait via
 * la page Utilisateurs, pas ici (on ne réécrase jamais un compte présent).
 *
 * Pour désactiver tout seed (ex. en prod après bootstrap), laisser les
 * mots de passe vides.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final AuthUserRepository users;
    private final PasswordEncoder encoder;

    private final String superAdminEmail;
    private final String superAdminPassword;
    private final String superAdminName;

    private final String itAdminEmail;
    private final String itAdminPassword;
    private final String itAdminName;

    public AdminSeeder(AuthUserRepository users,
                       PasswordEncoder encoder,
                       @Value("${app.admin.email:admin@sigdep.ci}") String superAdminEmail,
                       @Value("${app.admin.password:ChangeMe2026!}") String superAdminPassword,
                       @Value("${app.admin.display-name:Super Administrateur SIGDEP}") String superAdminName,
                       @Value("${app.it-admin.email:it-admin@sigdep.ci}") String itAdminEmail,
                       @Value("${app.it-admin.password:ChangeMe2026!}") String itAdminPassword,
                       @Value("${app.it-admin.display-name:Administrateur Technique SIGDEP}") String itAdminName) {
        this.users = users;
        this.encoder = encoder;
        this.superAdminEmail = superAdminEmail;
        this.superAdminPassword = superAdminPassword;
        this.superAdminName = superAdminName;
        this.itAdminEmail = itAdminEmail;
        this.itAdminPassword = itAdminPassword;
        this.itAdminName = itAdminName;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(superAdminEmail, superAdminPassword, superAdminName, "SUPER_ADMIN");
        seed(itAdminEmail, itAdminPassword, itAdminName, "IT_ADMIN");
    }

    /** Crée le compte s'il n'existe pas déjà (idempotent par email). */
    private void seed(String email, String password, String displayName, String role) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return; // compte non configuré → on ne seed rien
        }
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            return; // déjà présent → on ne touche pas
        }

        AuthUser u = new AuthUser();
        u.setEmail(normalized);
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(displayName);
        u.setRole(role);
        u.setUserLevel("NATIONAL");
        u.setActive(true);
        u.setPasswordExpired(false);
        users.save(u);

        log.info("Compte {} initial créé pour {}", role, normalized);
    }
}
