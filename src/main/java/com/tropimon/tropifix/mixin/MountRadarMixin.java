package com.tropimon.tropifix.mixin;

import com.tropimon.tropifix.radar.MountRadar;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.JumpingMount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Après le dessin de la barre de saut / d'endurance de la monture, redessine le radar Tropimon
 * par-dessus s'il a quelque chose à montrer.
 *
 * Quand TropimodClient affiche déjà le radar à la place de la barre (barre vide), il annule ce
 * dessin dès le début : cette injection en fin de méthode n'est alors pas atteinte, donc pas de
 * double affichage. require = 0 : si la méthode change de nom, le jeu se lance quand même.
 */
@Mixin(InGameHud.class)
public class MountRadarMixin {

    @Inject(method = "renderMountJumpBar", at = @At("TAIL"), require = 0)
    private void tropifix$radarOverMountBar(JumpingMount mount, DrawContext context, int x, CallbackInfo ci) {
        MountRadar.renderOverMountBar(context);
    }
}
