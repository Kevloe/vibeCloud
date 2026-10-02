package de.kevloe.vibecloud.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert eine Methode als Event-Handler (PLAN.md Abschnitt 10a).
 *
 * <p>Die Methode muss genau einen Parameter haben: den Event-Typ.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    Priority priority() default Priority.NORMAL;

    /**
     * Nur fuer abbrechbare Pre-Events: Was gilt, wenn dieser Handler in den Timeout laeuft.
     *
     * <p>Jeder Handler entscheidet das selbst, weil nur er weiss, was auf dem Spiel steht.
     * Eine Ban-Pruefung nutzt {@link TimeoutAction#DENY} - haengt sie, darf ein gebannter
     * Spieler nicht durchrutschen. Ein Handler, der nur eine Begruessung vorbereitet,
     * nutzt {@link TimeoutAction#ALLOW}.
     */
    TimeoutAction onTimeout() default TimeoutAction.ALLOW;
}
