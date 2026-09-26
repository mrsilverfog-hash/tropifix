package com.tropimon.tropifix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Corrige la deconnexion "Erreur de protocole reseau" provoquee par Farsight
 * (et plus generalement par tout mod qui garde des chunks en cache cote client).
 *
 * Quand le serveur renvoie un chunk deja present dans le cache, Farsight appelle
 * ClientWorld.unloadBlockEntities sur l'ancien chunk, qui appelle WorldChunk.clear().
 * Si la map des block entities de ce chunk est corrompue (entree null), le
 * forEach vanilla leve une NullPointerException. Comme elle survient pendant le
 * traitement du paquet level_chunk_with_light, Minecraft deconnecte le joueur.
 *
 * On enveloppe clear() : si la version vanilla plante, on vide le chunk a la main
 * en ignorant les entrees cassees. Les block entities marquees "removed" sont
 * ensuite retirees d'elles-memes de la liste de tick du monde.
 *
 * Aucune dependance aux classes de Farsight : le correctif reste valable quand
 * le pack met Farsight a jour.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkClearCrashMixin {

    private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    private static boolean tropifix$premierPassageSignale = false;
    private static int tropifix$crashsEvites = 0;

    @Shadow
    @Final
    private Map<BlockPos, ?> blockEntityTickers;

    @Shadow
    public abstract Map<BlockPos, BlockEntity> getBlockEntities();

    @WrapMethod(method = "clear()V", require = 1)
    private void tropifix$viderSansCrash(Operation<Void> original) {
        if (!tropifix$premierPassageSignale) {
            tropifix$premierPassageSignale = true;
            TROPIFIX$LOG.info("[TropiFix] Garde WorldChunk.clear active (injection appliquee).");
        }

        try {
            original.call();
        } catch (NullPointerException e) {
            tropifix$crashsEvites++;
            if (tropifix$crashsEvites == 1) {
                TROPIFIX$LOG.warn("[TropiFix] Deconnexion evitee : cache de chunk corrompu dans WorldChunk.clear (souvent Farsight).", e);
            } else {
                TROPIFIX$LOG.warn("[TropiFix] Deconnexion evitee dans WorldChunk.clear (occurrence n°{}).", tropifix$crashsEvites);
            }
            tropifix$viderManuellement();
        }
    }

    private void tropifix$viderManuellement() {
        Map<BlockPos, BlockEntity> blockEntities = this.getBlockEntities();

        List<BlockEntity> copie = new ArrayList<>();
        try {
            for (BlockEntity blockEntity : blockEntities.values()) {
                if (blockEntity != null) {
                    copie.add(blockEntity);
                }
            }
        } catch (RuntimeException ignoree) {
            // Map trop abimee pour etre parcourue : on garde ce qu'on a pu recuperer.
        }

        for (BlockEntity blockEntity : copie) {
            try {
                blockEntity.markRemoved();
            } catch (RuntimeException ignoree) {
                // Une block entity cassee ne doit pas empecher de vider les autres.
            }
        }

        try {
            blockEntities.clear();
        } catch (RuntimeException ignoree) {
        }

        try {
            this.blockEntityTickers.clear();
        } catch (RuntimeException ignoree) {
        }
    }
}
