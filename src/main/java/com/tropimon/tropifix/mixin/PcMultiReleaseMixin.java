package com.tropimon.tropifix.mixin;

import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.gui.pc.StorageWidget;
import com.cobblemon.mod.common.net.messages.server.storage.pc.ReleasePCPokemonPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relâche multiple dans l'écran du PC Cobblemon.
 *
 * Deux boutons au-dessus de l'écran du PC :
 *  - « Sélection multiple » : le clic gauche (clic ou cliquer-glisser) sélectionne des Pokémon
 *    au lieu de les déplacer ;
 *  - « Relâcher (N) » : confirmation détaillée (boîte + liste des Pokémon), puis envoi de
 *    ReleasePCPokemonPacket pour chaque Pokémon (le même paquet que le bouton d'origine).
 *
 * Sélection Pokémon par Pokémon uniquement, dans la vue boîte. En vue liste des boîtes
 * (« Box List 1-30 »), la sélection est désactivée : les clics vont à la liste.
 *
 * Tout est vérifié sur le contenu réel du PC (données Cobblemon côté client) : au moment de relâcher,
 * un Pokémon qui n'est plus à sa place n'est pas touché.
 */
@Mixin(PCGUI.class)
public abstract class PcMultiReleaseMixin extends Screen {

    @Unique private static final Logger TROPIFIX$LOG = LoggerFactory.getLogger("tropifix");
    @Unique private static final int GUI_HEIGHT = 205;
    @Unique private static final Pattern TROPIFIX$LIST_RANGE = Pattern.compile("(?i)(?:box\\s*list|liste)[^0-9]*(\\d+)\\s*-\\s*(\\d+)");

    @Shadow private StorageWidget storageWidget;

    @Unique private boolean tropifix$multi = false;
    @Unique private boolean tropifix$dragging = false;
    @Unique private boolean tropifix$dragAdd = true;
    @Unique private boolean tropifix$confirming = false;
    /** clé (boîte << 16 | emplacement) -> identifiant du Pokémon */
    @Unique private final Map<Long, UUID> tropifix$selection = new LinkedHashMap<>();

    @Unique private ButtonWidget tropifix$toggleBtn;
    @Unique private ButtonWidget tropifix$releaseBtn;
    @Unique private ButtonWidget tropifix$yesBtn;
    @Unique private ButtonWidget tropifix$noBtn;

    protected PcMultiReleaseMixin(Text title) {
        super(title);
    }

    @Unique private static long tropifix$key(int box, int slot) { return ((long) box << 16) | (slot & 0xFFFF); }
    @Unique private static int tropifix$boxOf(long key) { return (int) (key >> 16); }
    @Unique private static int tropifix$slotOf(long key) { return (int) (key & 0xFFFF); }

    // ------------------------------------------------------------------ boutons

    @Inject(method = "init()V", at = @At("TAIL"))
    private void tropifix$addButtons(CallbackInfo ci) {
        int w = 110, h = 20, gap = 4;
        int total = 2 * w + gap;
        int x0 = (this.width - total) / 2;
        // Au-dessus de l'écran du PC (le dessous est occupé par les boutons de tri de PCSorter)
        int y = Math.max(2, (this.height - GUI_HEIGHT) / 2 - h - 4);

        tropifix$toggleBtn = ButtonWidget.builder(Text.literal("Sélection multiple"), b -> {
            tropifix$multi = !tropifix$multi;
            if (!tropifix$multi) tropifix$selection.clear();
        }).dimensions(x0, y, w, h).build();

        tropifix$releaseBtn = ButtonWidget.builder(Text.literal("Relâcher (0)"), b -> {
            if (!tropifix$selection.isEmpty()) tropifix$confirming = true;
        }).dimensions(x0 + w + gap, y, w, h).build();

        int cy = this.height / 2 + 40;
        tropifix$yesBtn = ButtonWidget.builder(Text.literal("§cOui, relâcher"), b -> tropifix$releaseSelected())
            .dimensions(this.width / 2 - 104, cy, 100, h).build();
        tropifix$noBtn = ButtonWidget.builder(Text.literal("Non"), b -> tropifix$confirming = false)
            .dimensions(this.width / 2 + 4, cy, 100, h).build();

        this.addDrawableChild(tropifix$toggleBtn);
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
        tropifix$toggleBtn.visible = tropifix$releaseBtn.visible = ready && !tropifix$confirming;
        tropifix$releaseBtn.active = !tropifix$selection.isEmpty();
        tropifix$yesBtn.visible = tropifix$noBtn.visible = tropifix$confirming;
    }

    // ------------------------------------------------------------------ souris

    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true)
    private void tropifix$onClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        tropifix$boxCache.clear();
        if (tropifix$confirming) {
            // Pendant la confirmation, seuls Oui / Non répondent
            if (tropifix$yesBtn.isMouseOver(mx, my) || tropifix$noBtn.isMouseOver(mx, my)) return;
            cir.setReturnValue(true);
            return;
        }
        if (!tropifix$multi || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || this.storageWidget == null) return;
        if (tropifix$isOwnButton(mx, my)) return;

        // Vue liste des boîtes : pas de sélection, le clic va à la liste
        if (tropifix$listMode() != 0) return;
        // Un autre élément est sous la souris (menu d'un autre mod...) : on le laisse répondre
        if (tropifix$otherWidgetAt(mx, my)) return;
        Object slot = tropifix$slotAt(mx, my);
        if (slot == null) return; // clic ailleurs (navigation...) : comportement normal
        Integer index = tropifix$slotIndex(slot);
        if (index == null) return;
        int box = tropifix$currentBox();
        tropifix$dragAdd = !tropifix$selection.containsKey(tropifix$key(box, index));
        tropifix$applySlot(box, index);
        tropifix$dragging = true;
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged(DDIDD)Z", at = @At("HEAD"), cancellable = true)
    private void tropifix$onDrag(double mx, double my, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        tropifix$boxCache.clear();
        if (!tropifix$multi || !tropifix$dragging || this.storageWidget == null) return;
        if (tropifix$listMode() != 0) return;
        Object slot = tropifix$otherWidgetAt(mx, my) ? null : tropifix$slotAt(mx, my);
        if (slot != null) {
            Integer index = tropifix$slotIndex(slot);
            if (index != null) tropifix$applySlot(tropifix$currentBox(), index);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void tropifix$applySlot(int box, int index) {
        long key = tropifix$key(box, index);
        if (tropifix$dragAdd) {
            Pokemon p = tropifix$pokemonAt(box, index);
            if (p != null) tropifix$selection.put(key, p.getUuid());
        } else {
            tropifix$selection.remove(key);
        }
    }

    // ------------------------------------------------------------------ rendu

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
    private void tropifix$onRender(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        tropifix$boxCache.clear();
        // Fin du glissé quand le bouton gauche est relâché
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) tropifix$dragging = false;

        if (this.storageWidget != null) {
            // Retire de la sélection les Pokémon qui ne sont plus à leur place (données du PC)
            if (!tropifix$selection.isEmpty()) {
                tropifix$selection.entrySet().removeIf(e -> {
                    Pokemon p = tropifix$pokemonAt(tropifix$boxOf(e.getKey()), tropifix$slotOf(e.getKey()));
                    return p == null || !p.getUuid().equals(e.getValue());
                });
            }
            if (!tropifix$selection.isEmpty()) {
                int listMode = tropifix$listMode();
                int current = tropifix$currentBox();
                for (Object slot : tropifix$boxSlots()) {
                    if (!(slot instanceof ClickableWidget w) || !w.visible) continue;
                    Integer raw = tropifix$rawSlotIndex(slot);
                    if (raw == null) continue;
                    boolean highlight = listMode == 0 && tropifix$selection.containsKey(tropifix$key(current, raw));
                    if (highlight) {
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
            int n = tropifix$selection.size();
            List<String> names = tropifix$nameLines();
            String l1 = "Attention, " + n + " Pokémon " + tropifix$boxesLabel()
                + (n > 1 ? " vont être relâchés :" : " va être relâché :");
            int y = this.height / 2 - 30 - 10 * Math.min(4, names.size());
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§c" + l1), this.width / 2, y, 0xFFFFFF);
            y += 14;
            for (String line : names) {
                ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§e" + line), this.width / 2, y, 0xFFFFFF);
                y += 10;
            }
            y += 4;
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§fIls seront définitivement perdus. Valider ?"),
                this.width / 2, y, 0xFFFFFF);
            // Les boutons Oui / Non sont redessinés par-dessus le voile
            tropifix$yesBtn.render(ctx, mouseX, mouseY, delta);
            tropifix$noBtn.render(ctx, mouseX, mouseY, delta);
        }
    }

    /** « de la boîte 3 (Nom) » ou « des boîtes 3, 5 et 7 ». */
    @Unique
    private String tropifix$boxesLabel() {
        TreeSet<Integer> boxes = new TreeSet<>();
        for (Long k : tropifix$selection.keySet()) boxes.add(tropifix$boxOf(k));
        if (boxes.size() == 1) return "de la " + tropifix$boxLabel(boxes.first());
        List<String> nums = new ArrayList<>();
        for (int b : boxes) nums.add(String.valueOf(b + 1));
        if (nums.size() > 12) {
            return "de " + nums.size() + " boîtes (" + String.join(", ", nums.subList(0, 10)) + "…)";
        }
        String last = nums.remove(nums.size() - 1);
        return "des boîtes " + String.join(", ", nums) + " et " + last;
    }

    /** Noms des Pokémon sélectionnés, regroupés (« Rattata ×3 ») et répartis sur quelques lignes. */
    @Unique
    private List<String> tropifix$nameLines() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Long k : tropifix$selection.keySet()) {
            Pokemon p = tropifix$pokemonAt(tropifix$boxOf(k), tropifix$slotOf(k));
            if (p == null) continue;
            String name;
            try {
                name = p.getSpecies().getTranslatedName().getString();
            } catch (Throwable t) {
                name = p.getSpecies().getName();
            }
            if (p.getShiny()) name = name + " ✨";
            counts.merge(name, 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int maxWidth = Math.min(this.width - 20, 360);
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            String item = c.getKey() + (c.getValue() > 1 ? " ×" + c.getValue() : "");
            String candidate = cur.length() == 0 ? item : cur + ", " + item;
            if (cur.length() > 0 && this.textRenderer.getWidth(candidate) > maxWidth) {
                lines.add(cur.toString());
                cur = new StringBuilder(item);
            } else {
                cur = new StringBuilder(candidate);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        if (lines.size() > 6) {
            List<String> cut = new ArrayList<>(lines.subList(0, 5));
            cut.add("…");
            return cut;
        }
        return lines;
    }

    // ------------------------------------------------------------------ relâche

    @Unique
    private void tropifix$releaseSelected() {
        tropifix$confirming = false;
        tropifix$boxCache.clear();
        Map<Integer, Integer> perBox = new TreeMap<>();
        int count = 0;
        for (Map.Entry<Long, UUID> e : tropifix$selection.entrySet()) {
            int box = tropifix$boxOf(e.getKey());
            int slot = tropifix$slotOf(e.getKey());
            Pokemon now = tropifix$pokemonAt(box, slot);
            if (now == null || !now.getUuid().equals(e.getValue())) continue; // a bougé : on ne touche à rien
            try {
                new ReleasePCPokemonPacket(e.getValue(), new PCPosition(box, slot)).sendToServer();
                perBox.merge(box + 1, 1, Integer::sum);
                count++;
            } catch (Throwable t) {
                TROPIFIX$LOG.warn("[TropiFix] Relâche impossible (boîte {}, emplacement {})", box + 1, slot, t);
            }
        }
        TROPIFIX$LOG.info("[TropiFix] {} Pokémon relâchés (par boîte : {})", count, perBox);
        tropifix$selection.clear();
        tropifix$multi = false;
    }

    // ------------------------------------------------------------------ vue liste des boîtes

    /**
     * 0 = vue boîte normale ; n > 0 = vue liste des boîtes dont la première case est la boîte n
     * (« Box List 1-30 » -> 1, « Box List 31-60 » -> 31) ; -1 = vue liste dont la page est inconnue.
     * Le titre est cherché dans les éléments de l'écran et dans les champs du PC.
     */
    @Unique
    private int tropifix$listMode() {
        boolean sawListButton = false;
        List<Object> candidates = new ArrayList<>(this.children());
        candidates.addAll(tropifix$widgetFields(this));
        if (this.storageWidget != null) candidates.addAll(tropifix$widgetFields(this.storageWidget));
        for (Object o : candidates) {
            String text = tropifix$textOf(o);
            if (text == null || text.isEmpty()) continue;
            Matcher m = TROPIFIX$LIST_RANGE.matcher(text);
            if (m.find()) {
                try {
                    return Math.max(1, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
            // Le bouton « Box View » n'apparaît que dans la vue liste
            if (text.trim().equalsIgnoreCase("Box View") || text.trim().equalsIgnoreCase("Vue boîte")) sawListButton = true;
        }
        return sawListButton ? -1 : 0;
    }

    @Unique
    private static String tropifix$textOf(Object o) {
        try {
            if (o instanceof TextFieldWidget tf) {
                String t = tf.getText();
                if (t != null && !t.isEmpty()) return t;
            }
            if (o instanceof ClickableWidget w) {
                if (!w.visible) return null;
                return w.getMessage().getString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique private static final Map<Class<?>, List<Field>> TROPIFIX$WIDGET_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Widgets rangés dans les champs d'un objet (titre de boîte, boutons...), toutes classes parentes comprises. */
    @Unique
    private static List<Object> tropifix$widgetFields(Object owner) {
        List<Field> fields = TROPIFIX$WIDGET_FIELDS.computeIfAbsent(owner.getClass(), c -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!ClickableWidget.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return out;
        });
        List<Object> values = new ArrayList<>();
        for (Field f : fields) {
            try {
                Object v = f.get(owner);
                if (v != null) values.add(v);
            } catch (Throwable ignored) {
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ accès au widget du PC

    @Unique
    private boolean tropifix$isOwnButton(double mx, double my) {
        for (ButtonWidget b : new ButtonWidget[]{tropifix$toggleBtn, tropifix$releaseBtn}) {
            if (b != null && b.visible && b.isMouseOver(mx, my)) return true;
        }
        return false;
    }

    @Unique private static Method tropifix$getBox;
    @Unique private static Field tropifix$boxSlotsField;
    @Unique private static Field tropifix$pcField;

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
            if (slot instanceof ClickableWidget w
                    && mx >= w.getX() && mx < w.getX() + w.getWidth()
                    && my >= w.getY() && my < w.getY() + w.getHeight()) {
                return slot;
            }
        }
        return null;
    }

    /** Numéro de la case (0 à 29), quelle que soit la boîte. */
    @Unique
    private static Integer tropifix$rawSlotIndex(Object slot) {
        try {
            Object pos = slot.getClass().getMethod("getPosition").invoke(slot);
            if (pos instanceof PCPosition p) return p.getSlot();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Emplacement du slot, seulement s'il appartient à la boîte affichée. */
    @Unique
    private Integer tropifix$slotIndex(Object slot) {
        try {
            Object pos = slot.getClass().getMethod("getPosition").invoke(slot);
            if (pos instanceof PCPosition p && p.getBox() == tropifix$currentBox()) return p.getSlot();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Vrai si un élément de l'écran autre que le PC lui-même (menu d'un autre mod...) est sous la souris. */
    @Unique
    private boolean tropifix$otherWidgetAt(double mx, double my) {
        for (Element el : this.children()) {
            if (el == this.storageWidget || el == tropifix$toggleBtn
                || el == tropifix$releaseBtn || el == tropifix$yesBtn || el == tropifix$noBtn) continue;
            if (el instanceof com.cobblemon.mod.common.client.gui.pc.StorageSlot) continue;
            if (el instanceof ClickableWidget w && !w.visible) continue;
            try {
                if (el.isMouseOver(mx, my)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Unique
    private List<?> tropifix$boxes() {
        try {
            if (tropifix$pcField == null) {
                tropifix$pcField = StorageWidget.class.getDeclaredField("pc");
                tropifix$pcField.setAccessible(true);
            }
            Object pc = tropifix$pcField.get(this.storageWidget);
            Object boxes = pc.getClass().getMethod("getBoxes").invoke(pc);
            if (boxes instanceof List<?> l) return l;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private int tropifix$boxCount() {
        List<?> l = tropifix$boxes();
        return l == null ? 0 : l.size();
    }

    /** Contenu des boîtes déjà lu pendant l'image ou le clic en cours (vidé à chaque image). */
    @Unique private final Map<Integer, List<Pokemon>> tropifix$boxCache = new java.util.HashMap<>();

    /** Contenu réel de la boîte (données du PC côté client), null si illisible. */
    @Unique
    private List<Pokemon> tropifix$boxData(int box) {
        if (tropifix$boxCache.containsKey(box)) return tropifix$boxCache.get(box);
        List<Pokemon> data = tropifix$readBoxData(box);
        tropifix$boxCache.put(box, data);
        return data;
    }

    @Unique
    private List<Pokemon> tropifix$readBoxData(int box) {
        try {
            List<?> l = tropifix$boxes();
            if (l == null || box < 0 || box >= l.size()) return null;
            Object b = l.get(box);
            Object slots = b.getClass().getMethod("getSlots").invoke(b);
            if (!(slots instanceof List<?> sl)) return null;
            List<Pokemon> out = new ArrayList<>(sl.size());
            for (Object o : sl) out.add(o instanceof Pokemon pk ? pk : null);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private Pokemon tropifix$pokemonAt(int box, int slot) {
        List<Pokemon> data = tropifix$boxData(box);
        return data != null && slot >= 0 && slot < data.size() ? data.get(slot) : null;
    }

    /** « boîte 3 » ou « boîte 3 (Shiny) » si la boîte a un nom. */
    @Unique
    private String tropifix$boxLabel(int box) {
        String label = "boîte " + (box + 1);
        try {
            List<?> l = tropifix$boxes();
            if (l != null && box >= 0 && box < l.size()) {
                Object b = l.get(box);
                Object name = b.getClass().getMethod("getName").invoke(b);
                String n = name instanceof Text t ? t.getString() : (name == null ? "" : String.valueOf(name));
                if (!n.isBlank()) label += " (" + n + ")";
            }
        } catch (Throwable ignored) {
        }
        return label;
    }
}
