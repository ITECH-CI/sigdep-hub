package ci.itechciv.sigdep.hub.console.mail;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Envoi des emails transactionnels (réinitialisation / définition de mot de
 * passe, bienvenue, notifications de compte).
 *
 * Rend un template Thymeleaf HTML + sa version texte (template {@code .txt}
 * du même nom) et envoie un email multipart. Quand {@code app.mail.enabled}
 * vaut {@code false} (défaut dev), rien n'est envoyé : le contenu est LOGUÉ
 * en console — on peut donc dérouler tout le flux sans serveur SMTP.
 *
 * L'envoi est best-effort : une erreur SMTP est journalisée mais ne propage
 * jamais d'exception à l'appelant (créer un compte ne doit pas échouer parce
 * que le mail de bienvenue n'est pas parti).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final boolean enabled;
    private final String from;

    public EmailService(JavaMailSender mailSender,
                        TemplateEngine templateEngine,
                        @Value("${app.mail.enabled:false}") boolean enabled,
                        @Value("${app.mail.from:SIGDEP-3 <no-reply@sigdep.ci>}") String from) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.enabled = enabled;
        this.from = from;
    }

    /**
     * Rend et envoie un email.
     *
     * @param to        destinataire
     * @param subject   sujet
     * @param template  nom de base du template (ex. {@code reset-password}) ;
     *                  on attend {@code mail/<template>.html} et
     *                  {@code mail/<template>.txt} dans les resources.
     * @param variables variables de contexte Thymeleaf
     */
    public void send(String to, String subject, String template, Map<String, Object> variables) {
        Context ctx = new Context();
        ctx.setVariables(variables);
        String html = templateEngine.process("mail/" + template + ".html", ctx);
        String text = templateEngine.process("mail/" + template + ".txt", ctx);

        if (!enabled) {
            log.info("""
                    [MAIL désactivé — non envoyé]
                    À      : {}
                    Sujet  : {}
                    Texte  :
                    {}
                    """, to, subject, text);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html); // texte (fallback) + HTML
            mailSender.send(message);
            log.info("Email '{}' envoyé à {}", template, to);
        } catch (Exception ex) {
            log.error("Échec d'envoi de l'email '{}' à {} : {}", template, to, ex.toString());
        }
    }
}
