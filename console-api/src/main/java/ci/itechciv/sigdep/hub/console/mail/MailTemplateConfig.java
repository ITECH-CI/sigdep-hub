package ci.itechciv.sigdep.hub.console.mail;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Resolvers Thymeleaf dédiés aux emails. {@link EmailService} appelle
 * {@code process("mail/<nom>.html")} et {@code process("mail/<nom>.txt")} avec
 * le suffixe explicite ; on enregistre donc deux resolvers (HTML et TEXT) qui
 * matchent par motif et n'ajoutent aucun suffixe. Ils s'ajoutent au moteur
 * auto-configuré par Spring Boot (qui reste utilisé pour d'éventuelles autres
 * vues), sans interférer grâce aux {@code resolvablePatterns}.
 */
@Configuration
public class MailTemplateConfig {

    @Bean
    public ClassLoaderTemplateResolver mailHtmlTemplateResolver() {
        return resolver(TemplateMode.HTML, "mail/*.html", 1);
    }

    @Bean
    public ClassLoaderTemplateResolver mailTextTemplateResolver() {
        return resolver(TemplateMode.TEXT, "mail/*.txt", 2);
    }

    private static ClassLoaderTemplateResolver resolver(TemplateMode mode, String pattern, int order) {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix("");                 // le suffixe (.html/.txt) est dans le nom fourni
        r.setTemplateMode(mode);
        r.setCharacterEncoding("UTF-8");
        r.setCacheable(true);
        r.setResolvablePatterns(Set.of(pattern));
        r.setOrder(order);
        return r;
    }

    /** Branche les deux resolvers email sur le moteur Thymeleaf auto-configuré. */
    @Bean
    public MailResolverRegistrar mailResolverRegistrar(
            SpringTemplateEngine engine,
            ClassLoaderTemplateResolver mailHtmlTemplateResolver,
            ClassLoaderTemplateResolver mailTextTemplateResolver) {
        engine.addTemplateResolver(mailHtmlTemplateResolver);
        engine.addTemplateResolver(mailTextTemplateResolver);
        return new MailResolverRegistrar();
    }

    /** Marqueur (le bean ne sert qu'à forcer l'enregistrement au démarrage). */
    public static final class MailResolverRegistrar { }
}
