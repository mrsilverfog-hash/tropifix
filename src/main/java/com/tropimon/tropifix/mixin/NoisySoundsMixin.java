package com.tropimon.tropifix.mixin;

import java.util.Set;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coupe uniquement les sons des distributeurs / droppers et des wagonnets en mouvement,
 * sans toucher au volume des blocs : casser, poser, marcher... restent audibles.
 *
 * Le son est simplement annulé avant d'être joué (SoundManager.play), d'après son identifiant.
 */
@Mixin(SoundManager.class)
public abstract class NoisySoundsMixin {

    private static final Set<Identifier> TROPIFIX$MUTED = Set.of(
        // Distributeurs et droppers
        Identifier.ofVanilla("block.dispenser.dispense"),
        Identifier.ofVanilla("block.dispenser.launch"),
        Identifier.ofVanilla("block.dispenser.fail"),
        // Wagonnets en mouvement
        Identifier.ofVanilla("entity.minecart.riding"),
        Identifier.ofVanilla("entity.minecart.inside"),
        Identifier.ofVanilla("entity.minecart.inside.underwater")
    );

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void tropifix$muteNoisy(SoundInstance sound, CallbackInfo ci) {
        if (sound != null && TROPIFIX$MUTED.contains(sound.getId())) {
            ci.cancel();
        }
    }
}
