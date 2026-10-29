package com.tropimon.tropifix.mixin;

import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.gui.pc.StorageWidget;
import com.cobblemon.mod.common.net.messages.server.storage.pc.ReleasePCPokemonPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relâche multiple dans l'écran des boîtes du PC Cobblemon.
 *
 * Trois boutons au-dessus de l'écran du PC :
 *  - « Sélection multiple » : active un mode où le clic gauche (maintenu et glissé) sélectionne
 *    ou désélectionne les Pokémon de la boîte au lieu de les déplacer ;
 *  - « Tout sélectionner » : sélectionne tous les Pokémon de la boîte affichée ;
 *  - « Relâcher (N) » : demande confirmation (« Attention, N Pokémon de la boîte X vont être
 *    relâchés. Valider ? »), puis envoie au serveur la relâche de chaque Pokémon sélectionné
 *    (paquet ReleasePCPokemonPacket de Cobblemon, le même que le bouton de relâche d'origine).
 *
 * La sélection ne vaut que pour la boîte affichée : changer de boîte la vide.
 * Mode désactivé = comportement normal du PC (déplacer, échanger...).
 */
@Mixin(PCGUI.class)
public abstract class PcMultiReleaseMixin extends Screen {

    @Unique private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    @Unique private static final int GUI_HEIGHT = 205;

    @Shadow private StorageWidget storageWidget;

    @Unique private boolean tropifix$multi = false;
    @Unique private boolean tropifix$dragging = false;
    @Unique private boolean tropifix$dragAdd = true;
    @Unique private boolean tropifix$confirming = false;
    @Unique private int tropifix$selBox = -1;
    /** emplacement dans la boîte -> identifiant du Pokémon */
    @Unique private final Map<Integer, UUID> tropifix$selection = new LinkedHashMap<>();

    @Unique private ButtonWidget tropifix$toggleBtn;
    @Unique private ButtonWidget tropifix$allBtn;
    @Unique private ButtonWidget tropifix$releaseBtn;
    @Unique private ButtonWidget tropifix$yesBtn;
    @Unique private ButtonWidget tropifix$noBtn;

    protected PcMultiReleaseMixin(Text title) {
        super(title);
    }

    // ------------------------------------------------------------------ boutons

    @Inject(method = "init()V", at = @At("TAIL"))
    private void tropifix$addButtons(CallbackInfo ci) {
        int w = 110, h = 20, gap = 4;
        int total = 3 * w + 2 * gap;
        int x0 = (this.width - total) / 2;
        // Au-dessus de l'écran du PC (le dessous est occupé par les boutons de tri de PCSorter)
        int y = Math.max(2, (this.height - GUI_HEIGHT) / 2 - h - 4);

        tropifix$toggleBtn = ButtonWidget.builder(Text.literal("Sélection multiple"), b -> {
            tropifix$multi = !tropifix$multi;
            if (!tropifix$multi) tropifix$selection.clear();
        }).dimensions(x0, y, w, h).build();

        tropifix$allBtn = ButtonWidget.builder(Text.literal("Tout sélectionner"), b -> {
            tropifix$multi = true;
            tropifix$selectAll();
        }).dimensions(x0 + w + gap, y, w, h).build();

        tropifix$releaseBtn = ButtonWidget.builder(Text.literal("Relâcher (0)"), b -> {
            if (!tropifix$selection.isEmpty()) tropifix$confirming = true;
        }).dimensions(x0 + 2 * (w + gap), y, w, h).build();

        int cy = this.height / 2 + 10;
        tropifix$yesBtn = ButtonWidget.builder(Text.literal("§cOui, relâcher"), b -> tropifix$releaseSelected())
            .dimensions(this.width / 2 - 104, cy, 100, h).build();
        tropifix$noBtn = ButtonWidget.builder(Text.literal("Non"), b -> tropifix$confirming = false)
            .dimensions(this.width / 2 + 4, cy, 100, h).build();

        this.addDrawableChild(tropifix$toggleBtn);
        this.addDrawableChild(tropifix$allBtn);
        this.addDrawableChild(tropifix$releaseBtn);
        this.addDrawableChild(tropifix$yesBtn);
        this.addDrawableChild(tropifix$noBtn);
        tropifix$refreshButtons();
    }

    @Unique
    private void tropifix$refreshButtons() {
        if (tropifix$toggleBtn == null) return;
        boolean ready = this.storageWidget != null;
        tropifix$toggleBtn.setMessage(Text.literal(tropifix$multi ? "§aSélection multiple : ON" : "Sélection multiple"));
        tropifix$releaseBtn.setMessage(Text.literal("Relâcher (" + tropifix$selection.size() + ")"));
        tropifix$toggleBtn.visible = tropifix$allBtn.visible = tropifix$releaseBtn.visible = ready && !tropifix$confirming;
        tropifix$releaseBtn.active = !tropifix$selection.isEmpty();
        tropifix$yesBtn.visible = tropifix$noBtn.visible = tropifix$confirming;
    }

    // ------------------------------------------------------------------ souris

    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true)
    private void tropifix$onClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (tropifix$confirming) {
            // Pendant la confirmation, seuls Oui / Non répondent
            if (tropifix$yesBtn.isMouseOver(mx, my) || tropifix$noBtn.isMouseOver(mx, my)) return;
            cir.setReturnValue(true);
            return;
        }
        if (!tropifix$multi || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || this.storageWidget == null) return;
        Object slot = tropifix$slotAt(mx, my);
        if (slot == null) return; // clic ailleurs (boutons, navigation...) : comportement normal
        Integer index = tropifix$slotIndex(slot);
        if (index == null) return;
        tropifix$syncBox();
        tropifix$dragAdd = !tropifix$selection.containsKey(index);
        tropifix$apply(slot, index);
        tropifix$dragging = true;
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged(DDIDD)Z", at = @At("HEAD"), cancellable = true)
    private void tropifix$onDrag(double mx, double my, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (!tropifix$multi || !tropifix$dragging || this.storageWidget == null) return;
        Object slot = tropifix$slotAt(mx, my);
        if (slot != null) {
            Integer index = tropifix$slotIndex(slot);
            if (index != null) tropifix$apply(slot, index);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void tropifix$apply(Object slot, int index) {
        if (tropifix$dragAdd) {
            Pokemon p = tropifix$pokemonOf(slot);
            if (p != null) tropifix$selection.put(index, p.getUuid());
        } else {
            tropifix$selection.remove(index);
        }
    }

    @Unique
    private void tropifix$selectAll() {
        if (this.storageWidget == null) return;
        tropifix$syncBox();
        tropifix$selection.clear();
        for (Object slot : tropifix$boxSlots()) {
            Integer index = tropifix$slotIndex(slot);
            Pokemon p = tropifix$pokemonOf(slot);
            if (index != null && p != null) tropifix$selection.put(index, p.getUuid());
        }
    }

    // ------------------------------------------------------------------ rendu

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
    private void tropifix$onRender(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // Fin du glissé quand le bouton gauche est relâché
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) tropifix$dragging = false;

        if (this.storageWidget != null) {
            tropifix$syncBox();
            // Retire de la sélection les emplacements vidés ou changés entre-temps
            if (!tropifix$selection.isEmpty()) {
                for (Object slot : tropifix$boxSlots()) {
                    Integer index = tropifix$slotIndex(slot);
                    if (index == null || !tropifix$selection.containsKey(index)) continue;
                    Pokemon p = tropifix$pokemonOf(slot);
                    if (p == null || !p.getUuid().equals(tropifix$selection.get(index))) {
                        tropifix$selection.remove(index);
                        continue;
                    }
                    if (slot instanceof ClickableWidget w) {
                        int x = w.getX(), y = w.getY(), sw = w.getWidth(), sh = w.getHeight();
                        ctx.fill(x, y, x + sw, y + sh, 0x66FF2020);
                        ctx.drawBorder(x, y, sw, sh, 0xFFFF3030);
                    }
                }
            }
        }

        tropifix$refreshButtons();

        if (tropifix$confirming) {
            ctx.fill(0, 0, this.width, this.height, 0xB0000000);
            int box = tropifix$selBox + 1;
            String l1 = "Attention, " + tropifix$selection.size() + " Pokémon de la boîte " + box + " vont être relâchés.";
            String l2 = "Ils seront définitivement perdus. Valider ?";
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§c" + l1), this.width / 2, this.height / 2 - 24, 0xFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§f" + l2), this.width / 2, this.height / 2 - 10, 0xFFFFFF);
            // Les boutons Oui / Non sont redessinés par-dessus le voile
            tropifix$yesBtn.render(ctx, mouseX, mouseY, delta);
            tropifix$noBtn.render(ctx, mouseX, mouseY, delta);
        }
    }

    // ------------------------------------------------------------------ relâche

    @Unique
    private void tropifix$releaseSelected() {
        tropifix$confirming = false;
        int box = tropifix$selBox;
        int count = 0;
        for (Map.Entry<Integer, UUID> e : tropifix$selection.entrySet()) {
            try {
                new ReleasePCPokemonPacket(e.getValue(), new PCPosition(box, e.getKey())).sendToServer();
                count++;
            } catch (Throwable t) {
                TROPIFIX$LOG.warn("[TropiFix] Relâche impossible (boîte {}, emplacement {})", box + 1, e.getKey(), t);
            }
        }
        TROPIFIX$LOG.info("[TropiFix] {} Pokémon relâchés depuis la boîte {}", count, box + 1);
        tropifix$selection.clear();
        tropifix$multi = false;
    }

    // ------------------------------------------------------------------ accès au widget du PC

    /** Vide la sélection si la boîte affichée a changé. */
    @Unique
    private void tropifix$syncBox() {
        int box = tropifix$currentBox();
        if (box != tropifix$selBox) {
            tropifix$selBox = box;
            tropifix$selection.clear();
            tropifix$confirming = false;
        }
    }

    @Unique private static Method tropifix$getBox;
    @Unique private static Field tropifix$boxSlotsField;

    @Unique
    private int tropifix$currentBox() {
        try {
            if (tropifix$getBox == null) tropifix$getBox = StorageWidget.class.getMethod("getBox");
            return (int) tropifix$getBox.invoke(this.storageWidget);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Unique
    private List<?> tropifix$boxSlots() {
        try {
            if (tropifix$boxSlotsField == null) {
                tropifix$boxSlotsField = StorageWidget.class.getDeclaredField("boxSlots");
                tropifix$boxSlotsField.setAccessible(true);
            }
            Object v = tropifix$boxSlotsField.get(this.storageWidget);
            if (v instanceof List<?> l) return l;
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    @Unique
    private Object tropifix$slotAt(double mx, double my) {
        for (Object slot : tropifix$boxSlots()) {
            if (slot instanceof ClickableWidget w && w.visible
                    && mx >= w.getX() && mx < w.getX() + w.getWidth()
                    && my >= w.getY() && my < w.getY() + w.getHeight()) {
                return slot;
            }
        }
        return null;
    }

    @Unique
    private static Integer tropifix$slotIndex(Object slot) {
        try {
            Object pos = slot.getClass().getMethod("getPosition").invoke(slot);
            if (pos instanceof PCPosition p) return p.getSlot();
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private static Pokemon tropifix$pokemonOf(Object slot) {
        try {
            Object p = slot.getClass().getMethod("getPokemon").invoke(slot);
            return p instanceof Pokemon pk ? pk : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
