package com.tropimon.tropifix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ConcurrentModificationException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Corrige le crash "ConcurrentModificationException" dans ChatHud.refresh().
 *
 * Tropimon Chat Filter appelle ChatHud.refresh() à la fermeture du tchat pour reconstruire
 * l'affichage des messages. Si la liste des messages est modifiée pendant cette reconstruction
 * (message reçu au même moment, autre mod qui réécrit le tchat...), l'itérateur lève une
 * ConcurrentModificationException et le jeu plante ("keyPressed event handler").
 *
 * refresh() ne fait que reconstruire les lignes affichées à partir des messages : si elle
 * échoue, on l'ignore et on la relance au tick suivant, une fois la modification terminée.
 * Au plus 3 nouvelles tentatives d'affilée, pour ne jamais tourner en boucle.
 */
@Mixin(ChatHud.class)
public abstract class ChatRefreshCrashMixin {

    private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    private static int tropifix$retries = 0;
    private static int tropifix$crashsEvites = 0;

    @WrapMethod(method = "refresh")
    private void tropifix$safeRefresh(Operation<Void> original) {
        try {
            original.call();
            tropifix$retries = 0;
        } catch (ConcurrentModificationException e) {
            tropifix$crashsEvites++;
            if (tropifix$crashsEvites == 1) {
                TROPIFIX$LOG.warn("[TropiFix] Crash du tchat évité (ChatHud.refresh modifié pendant la reconstruction), nouvelle tentative au tick suivant", e);
            } else {
                TROPIFIX$LOG.warn("[TropiFix] Crash du tchat évité (total : {})", tropifix$crashsEvites);
            }
            if (tropifix$retries < 3) {
                tropifix$retries++;
                ChatHud self = (ChatHud) (Object) this;
                MinecraftClient.getInstance().send(self::refresh);
            } else {
                tropifix$retries = 0;
            }
        }
    }
}
