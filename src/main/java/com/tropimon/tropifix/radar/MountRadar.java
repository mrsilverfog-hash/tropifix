package com.tropimon.tropifix.radar;

import net.minecraft.client.gui.DrawContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Radar Tropimon par-dessus la barre d'endurance de la monture.
 *
 * TropimodClient dessine son radar à la place de la barre de saut / d'endurance de la monture,
 * mais s'efface dès que cette barre est utilisée (vol, saut) : en vol, le radar disparaît.
 * Ici, une fois la barre de la monture dessinée, on redessine le radar au même endroit, par-dessus,
 * en appelant ContextBarManager.renderBar(DrawContext) de TropimodClient (par réflexion, sans
 * dépendance de compilation).
 *
 * renderBar ne dessine rien et renvoie false quand le radar n'a rien à montrer : dans ce cas la
 * barre d'endurance reste visible normalement. Le radar ne passe devant que s'il suit ou détecte
 * quelque chose.
 */
public final class MountRadar {

    private static final Logger LOG = LoggerFactory.getLogger("tropifix");
    private static final String MANAGER_CLASS = "fr.erusel.tropimodclient.client.hud.contextbar.ContextBarManager";

    private static boolean resolved = false;
    private static boolean errorLogged = false;
    private static Method renderBar = null;

    private MountRadar() {}

    public static void renderOverMountBar(DrawContext context) {
        if (!resolved) {
            resolved = true;
            try {
                renderBar = Class.forName(MANAGER_CLASS).getMethod("renderBar", DrawContext.class);
                renderBar.setAccessible(true);
                LOG.info("[Radar monture] Radar Tropimon branché par-dessus la barre de la monture.");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                LOG.info("[Radar monture] Radar Tropimon introuvable : fonction désactivée.");
                renderBar = null;
            }
        }
        if (renderBar == null) return;
        try {
            renderBar.invoke(null, context);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                LOG.warn("[Radar monture] Affichage du radar impossible", t);
            }
        }
    }
}
