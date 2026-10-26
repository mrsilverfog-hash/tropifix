package com.tropimon.tropifix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.CustomPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Corrige le crash "IllegalStateException: Cannot send packets when not in game!".
 *
 * Quand une notification Tropimon se termine, TropimodClient renvoie son résultat au serveur
 * via ClientPlayNetworking.send(...). Si le joueur vient d'être déconnecté (ou change de
 * serveur) à ce moment-là, il n'y a plus de connexion : Fabric lève une exception et le jeu
 * plante.
 *
 * Ici, si aucune connexion n'est active, le paquet est simplement abandonné (il n'aurait pu
 * arriver nulle part). Quand on est connecté, rien ne change.
 */
@Mixin(value = ClientPlayNetworking.class, remap = false)
public abstract class OfflinePacketCrashMixin {

    private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    private static int tropifix$paquetsAbandonnes = 0;

    @WrapMethod(method = "send", remap = false)
    private static void tropifix$sendIfConnected(CustomPayload payload, Operation<Void> original) {
        if (MinecraftClient.getInstance().getNetworkHandler() == null) {
            tropifix$paquetsAbandonnes++;
            TROPIFIX$LOG.warn("[TropiFix] Paquet {} abandonné : pas connecté à un serveur (crash évité, total : {})",
                payload == null ? "?" : payload.getId().id(), tropifix$paquetsAbandonnes);
            return;
        }
        original.call(payload);
    }
}
