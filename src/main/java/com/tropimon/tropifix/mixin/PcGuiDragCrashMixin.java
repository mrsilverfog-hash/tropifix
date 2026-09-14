package com.tropimon.tropifix.mixin;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Corrige un crash de Cobblemon 1.7.x : PCGUI.storageWidget est une propriete
 * Kotlin "lateinit" qui n'est assignee qu'a la reception des donnees de boites
 * envoyees par le serveur. Si le joueur clique-glisse dans l'ecran du PC avant
 * ce moment, mouseDragged lit le widget et Kotlin leve
 * UninitializedPropertyAccessException, ce qui fait planter le client.
 *
 * Deux noms de methode sont cibles volontairement : "mouseDragged" est le nom
 * yarn utilise a la compilation, "method_25403" le nom intermediary present
 * dans le jar remappe de Cobblemon a l'execution. Selon que le refmap couvre
 * ou non un membre herite de Minecraft dans une classe de mod, c'est l'un ou
 * l'autre qui correspond ; require reste a 0 pour que celui qui ne correspond
 * pas soit simplement ignore.
 *
 * Le test d'initialisation passe par le getter Kotlin dans un try/catch plutot
 * que par une lecture reflexive du champ de sauvegarde : c'est precisement
 * l'exception qu'on veut eviter qui sert de signal, donc aucune hypothese sur
 * le nom interne du champ n'est necessaire.
 */
@Mixin(PCGUI.class)
public abstract class PcGuiDragCrashMixin {

    private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    private static boolean tropifix$premierPassageSignale = false;
    private static boolean tropifix$blocageSignale = false;

    @Inject(
            method = {
                    "mouseDragged(DDIDD)Z",
                    "method_25403(DDIDD)Z"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void tropifix$ignorerGlisserAvantInitialisation(
            double sourisX,
            double sourisY,
            int bouton,
            double deltaX,
            double deltaY,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!tropifix$premierPassageSignale) {
            tropifix$premierPassageSignale = true;
            TROPIFIX$LOG.info("[TropiFix] Garde PCGUI.mouseDragged active (injection appliquee).");
        }

        if (!tropifix$widgetPret()) {
            if (!tropifix$blocageSignale) {
                tropifix$blocageSignale = true;
                TROPIFIX$LOG.info("[TropiFix] Glisser ignore : storageWidget pas encore initialise.");
            }
            cir.setReturnValue(false);
        }
    }

    private boolean tropifix$widgetPret() {
        try {
            return ((PCGUI) (Object) this).getStorageWidget() != null;
        } catch (Throwable erreur) {
            return false;
        }
    }
}
