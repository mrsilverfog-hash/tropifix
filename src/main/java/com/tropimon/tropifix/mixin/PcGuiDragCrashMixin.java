package com.tropimon.tropifix.mixin;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.gui.pc.StorageWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * Le champ est prive et sans getter public (verifie au javap sur le jar
 * remappe), donc on y accede par @Shadow : lecture directe du champ de
 * sauvegarde, sans passer par l'accesseur Kotlin qui est justement ce qui leve
 * l'exception. La version 1.0.0 tentait la meme chose par reflexion et
 * retombait silencieusement sur son cas d'echec, ce qui laissait le crash
 * se produire.
 *
 * require = 1 est volontaire : si une future version de Cobblemon renomme ou
 * supprime la methode, mieux vaut un echec visible au demarrage qu'une garde
 * qui ne protege plus rien sans le dire.
 */
@Mixin(PCGUI.class)
public abstract class PcGuiDragCrashMixin {

    private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    private static boolean tropifix$premierPassageSignale = false;
    private static boolean tropifix$blocageSignale = false;

    @Shadow
    private StorageWidget storageWidget;

    @Inject(
            method = "mouseDragged(DDIDD)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
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

        if (this.storageWidget == null) {
            if (!tropifix$blocageSignale) {
                tropifix$blocageSignale = true;
                TROPIFIX$LOG.info("[TropiFix] Glisser ignore : storageWidget pas encore initialise.");
            }
            cir.setReturnValue(false);
        }
    }
}
