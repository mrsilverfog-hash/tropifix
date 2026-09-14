package com.tropimon.tropifix.mixin;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Corrige un crash de Cobblemon 1.7.x : PCGUI.storageWidget est une propriete
 * Kotlin "lateinit" qui n'est assignee qu'a la reception des donnees de boites
 * envoyees par le serveur. Si le joueur clique-glisse dans l'ecran du PC avant
 * ce moment, mouseDragged lit le widget et Kotlin leve
 * UninitializedPropertyAccessException, ce qui fait planter le client.
 *
 * La latence reseau (serveur derriere Velocity) elargit nettement cette
 * fenetre, d'ou la reproductibilite en jeu.
 *
 * On annule simplement l'evenement tant que le widget n'existe pas : la valeur
 * de retour false est exactement ce que renvoie Screen.mouseDragged par defaut,
 * donc aucun comportement n'est modifie une fois le PC charge.
 */
@Mixin(PCGUI.class)
public abstract class PcGuiDragCrashMixin {

    private static Field tropifix$champStorageWidget;
    private static boolean tropifix$champDejaCherche = false;

    @Inject(
            method = "mouseDragged(DDIDD)Z",
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
        if (!tropifix$widgetPret(this)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Lit directement le champ de sauvegarde du lateinit. Le getter Kotlin, lui,
     * leve l'exception qu'on cherche justement a eviter, donc on ne peut pas
     * l'utiliser ici. Si le champ est introuvable (renommage dans une future
     * version de Cobblemon), on laisse passer l'appel normalement.
     */
    private static boolean tropifix$widgetPret(Object ecran) {
        try {
            if (!tropifix$champDejaCherche) {
                tropifix$champDejaCherche = true;
                for (Field champ : PCGUI.class.getDeclaredFields()) {
                    if ("storageWidget".equals(champ.getName())) {
                        champ.setAccessible(true);
                        tropifix$champStorageWidget = champ;
                        break;
                    }
                }
            }
            if (tropifix$champStorageWidget == null) {
                return true;
            }
            return tropifix$champStorageWidget.get(ecran) != null;
        } catch (Throwable erreur) {
            return true;
        }
    }
}
