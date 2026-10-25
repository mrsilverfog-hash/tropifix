package com.tropimon.tropifix.timer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Minuteur d'événements Tropimon.
 *
 * Un minuteur de 5 minutes apparaît à l'écran dès qu'une notification Tropimon annonce
 * un Raid, un Méga Raid ou un boss mondial. Il bipe quand il reste 1 minute et 30 secondes,
 * puis disparaît sans bruit à 0.
 *
 * Les notifications sont lues par réflexion dans le NotificationManager de TropimodClient
 * (aucune dépendance de compilation) : on reconnaît la clé de description
 * gui.tropimod.notification.raid.triggered / triggered_mega / scheduled.
 *
 * Déplacement : ouvrir le tchat (T), puis cliquer-glisser le minuteur. Clic droit dessus
 * (tchat ouvert) = fermer le minuteur en cours. La touche « Verrouiller le minuteur »
 * empêche tout déplacement. Position et verrouillage sont gardés dans
 * config/tropifix_timer.properties.
 */
public final class EventTimer {

    private static final Logger LOG = LoggerFactory.getLogger("tropifix");

    private static final Duration DURATION = Duration.ofMinutes(5);
    private static final long WARN_MS = 60_000L;
    private static final long WARN2_MS = 30_000L;
    private static final long TEST_DURATION_MS = 70_000L;

    // Taille du widget (coordonnées GUI mises à l'échelle)
    private static final int W = 62;
    private static final int H = 20;

    // État du minuteur
    private static long endMillis = -1L;          // -1 = aucun minuteur
    private static boolean warned = false;
    private static boolean warned30 = false;

    // Bips en attente : délai en ticks avant chaque note
    private static final int[] beepDelays = new int[8];
    private static final float[] beepPitches = new float[8];
    private static int beepCount = 0;

    // Position / déplacement
    private static int posX = 10;
    private static int posY = 60;
    private static boolean locked = false;
    private static boolean dragging = false;
    private static double dragOffX, dragOffY;

    private static KeyBinding lockKey;
    private static KeyBinding testKey;

    private EventTimer() {}

    public static void register() {
        loadConfig();

        lockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Verrouiller le minuteur", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "TropiFix"));
        testKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Tester le minuteur (1 min 10)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "TropiFix"));

        ClientTickEvents.END_CLIENT_TICK.register(EventTimer::tick);

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> render(ctx));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((scr, mx, my, button) -> {
                if (!isInside(mx, my)) return true;
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && endMillis > 0) {
                    endMillis = -1L; // fermer le minuteur
                    return false;
                }
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !locked) {
                    dragging = true;
                    dragOffX = mx - posX;
                    dragOffY = my - posY;
                    return false;
                }
                return true;
            });
            ScreenMouseEvents.afterMouseRelease(screen).register((scr, mx, my, button) -> {
                if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    dragging = false;
                    saveConfig();
                }
            });
            ScreenEvents.remove(screen).register(scr -> {
                if (dragging) {
                    dragging = false;
                    saveConfig();
                }
            });
        });
    }

    // ------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        while (lockKey.wasPressed()) {
            locked = !locked;
            saveConfig();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(locked
                    ? "§eMinuteur : position §cverrouillée"
                    : "§eMinuteur : position §adéverrouillée §7(tchat ouvert pour le déplacer)"), true);
            }
        }
        while (testKey.wasPressed()) {
            startTimer(System.currentTimeMillis() + TEST_DURATION_MS);
        }

        // Notifications lues 4 fois par seconde : une annonce reste affichée plusieurs secondes
        if (++notifPollTick >= 5) {
            notifPollTick = 0;
            try {
                pollRaidNotifications();
            } catch (Throwable t) {
                if (!notifErrorLogged) {
                    notifErrorLogged = true;
                    LOG.warn("[Minuteur] Lecture des notifications Tropimon impossible", t);
                }
            }
        }

        long now = System.currentTimeMillis();
        if (endMillis > 0) {
            long remaining = endMillis - now;
            if (!warned && remaining <= WARN_MS && remaining > 0) {
                warned = true;
                queueBeeps(new float[]{1.6f, 1.6f, 1.6f}, 6);
            }
            if (!warned30 && remaining <= WARN2_MS && remaining > 0) {
                warned30 = true;
                queueBeeps(new float[]{1.6f, 1.6f, 1.6f}, 6);
            }
            if (remaining <= 0) {
                endMillis = -1L; // fini : disparaît sans bruit
            }
        }

        // Bips en attente
        for (int i = 0; i < beepCount; i++) {
            if (--beepDelays[i] <= 0) {
                playPling(client, beepPitches[i]);
                // retire l'entrée i
                for (int j = i; j < beepCount - 1; j++) {
                    beepDelays[j] = beepDelays[j + 1];
                    beepPitches[j] = beepPitches[j + 1];
                }
                beepCount--;
                i--;
            }
        }
    }

    // ===================== NOTIFICATIONS DE RAID (TropimodClient) =====================

    private static final String NOTIF_MANAGER_CLASS = "fr.erusel.tropimodclient.client.notification.NotificationManager";
    private static final String NOTIF_CLASS = "fr.tropimon.tropimoncore.data.notification.Notification";
    private static final Set<String> RAID_KEYS = Set.of(
        "gui.tropimod.notification.raid.triggered",       // « X a déclenché un Raid ! »
        "gui.tropimod.notification.raid.triggered_mega",  // « X a déclenché un Méga Raid ! »
        "gui.tropimod.notification.raid.scheduled"        // « Un boss mondial est apparu ! »
    );

    private static boolean notifResolved = false;
    private static boolean notifErrorLogged = false;
    private static int notifPollTick = 0;
    private static Method notifCurrent = null;
    private static Method notifPinned = null;
    private static Field notifQueue = null;
    private static final Set<String> seenNotifIds = new HashSet<>();
    private static final Set<Object> seenNotifObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, Method> methodCache = new HashMap<>();

    private static void pollRaidNotifications() throws Exception {
        if (!notifResolved) {
            notifResolved = true;
            Class<?> mgr;
            try {
                mgr = Class.forName(NOTIF_MANAGER_CLASS);
            } catch (ClassNotFoundException e) {
                LOG.info("[Minuteur] TropimodClient absent : pas de minuteur automatique sur les raids.");
                return;
            }
            try { notifCurrent = mgr.getMethod("current"); } catch (NoSuchMethodException ignored) { }
            try { notifPinned = mgr.getMethod("pinned"); } catch (NoSuchMethodException ignored) { }
            try {
                notifQueue = mgr.getDeclaredField("QUEUE");
                notifQueue.setAccessible(true);
            } catch (NoSuchFieldException ignored) { }
        }
        if (notifCurrent == null && notifPinned == null && notifQueue == null) return;

        if (notifQueue != null && notifQueue.get(null) instanceof List<?> list) {
            Object[] snapshot;
            synchronized (list) {
                snapshot = list.toArray();
            }
            for (Object pending : snapshot) handleNotification(pending);
        }
        if (notifCurrent != null) handleNotification(notifCurrent.invoke(null));
        if (notifPinned != null) handleNotification(notifPinned.invoke(null));
    }

    /** Accepte une Notification, une ActiveNotification ou un Pending de la file d'attente. */
    private static void handleNotification(Object holder) throws Exception {
        if (holder == null) return;
        Object notif = holder.getClass().getName().equals(NOTIF_CLASS) ? holder : call(holder, "notification");
        if (notif == null || !notif.getClass().getName().equals(NOTIF_CLASS)) return;

        // Chaque notification n'est traitée qu'une fois
        Object idObj = call(notif, "id");
        String id = idObj == null ? "" : idObj.toString();
        if (!id.isEmpty()) {
            if (!seenNotifIds.add(id)) return;
        } else if (!seenNotifObjects.add(notif)) {
            return;
        }
        if (seenNotifIds.size() > 300) seenNotifIds.clear();
        if (seenNotifObjects.size() > 300) seenNotifObjects.clear();

        Object desc = call(notif, "description");
        Object key = desc == null ? null : call(desc, "value");
        if (key == null || !RAID_KEYS.contains(key.toString())) return;

        LOG.info("[Minuteur] Notification de raid ({}) : minuteur lancé", key);
        startTimer(System.currentTimeMillis() + DURATION.toMillis());
    }

    private static Object call(Object target, String name) throws Exception {
        if (target == null) return null;
        String k = target.getClass().getName() + "#" + name;
        Method m = methodCache.get(k);
        if (m == null) {
            try {
                m = target.getClass().getMethod(name);
            } catch (NoSuchMethodException e) {
                return null;
            }
            m.setAccessible(true);
            methodCache.put(k, m);
        }
        return m.invoke(target);
    }

    private static void startTimer(long end) {
        endMillis = end;
        warned = false;
        warned30 = false;
    }

    private static void queueBeeps(float[] pitches, int spacingTicks) {
        for (int k = 0; k < pitches.length && beepCount < beepDelays.length; k++) {
            beepDelays[beepCount] = 1 + k * spacingTicks;
            beepPitches[beepCount] = pitches[k];
            beepCount++;
        }
    }

    private static void playPling(MinecraftClient client, float pitch) {
        SoundEvent pling = SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
        client.getSoundManager().play(PositionedSoundInstance.master(pling, pitch, 1.0f));
    }

    // ------------------------------------------------------------------

    private static boolean isInside(double mx, double my) {
        boolean visible = endMillis > 0 || MinecraftClient.getInstance().currentScreen instanceof ChatScreen;
        return visible && mx >= posX && mx <= posX + W && my >= posY && my <= posY + H;
    }

    private static void render(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;

        boolean chatOpen = client.currentScreen instanceof ChatScreen;
        boolean active = endMillis > 0;
        if (!active && !chatOpen && !dragging) return;

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        if (dragging) {
            double mx = client.mouse.getX() * sw / client.getWindow().getWidth();
            double my = client.mouse.getY() * sh / client.getWindow().getHeight();
            posX = (int) Math.round(mx - dragOffX);
            posY = (int) Math.round(my - dragOffY);
        }
        posX = Math.max(0, Math.min(sw - W, posX));
        posY = Math.max(0, Math.min(sh - H, posY));

        long remaining = active ? endMillis - System.currentTimeMillis() : DURATION.toMillis();
        long secs = Math.max(0, (remaining + 999) / 1000);
        String label = String.format("%02d:%02d", secs / 60, secs % 60);

        int textColor;
        if (!active) {
            textColor = 0xFF888888;                       // aperçu (tchat ouvert, pas de minuteur)
        } else if (remaining <= WARN_MS) {
            // clignote en rouge / jaune pendant la dernière minute
            textColor = ((System.currentTimeMillis() / 500) % 2 == 0) ? 0xFFFF4040 : 0xFFFFDD33;
        } else {
            textColor = 0xFFFFFFFF;
        }

        int bg = active ? 0xB0000000 : 0x70000000;
        ctx.fill(posX, posY, posX + W, posY + H, bg);
        int border = locked ? 0xFF555555 : (chatOpen ? 0xFFFFDD33 : 0xFF888888);
        ctx.drawBorder(posX, posY, W, H, border);

        ctx.drawItem(new ItemStack(Items.CLOCK), posX + 2, posY + 2);
        ctx.drawTextWithShadow(client.textRenderer, label, posX + 22, posY + 6, textColor);
    }

    // ------------------------------------------------------------------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("tropifix_timer.properties");
    }

    private static void loadConfig() {
        Path p = configPath();
        if (!Files.exists(p)) return;
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p)) {
            props.load(r);
            posX = Integer.parseInt(props.getProperty("x", String.valueOf(posX)));
            posY = Integer.parseInt(props.getProperty("y", String.valueOf(posY)));
            locked = Boolean.parseBoolean(props.getProperty("locked", "false"));
        } catch (IOException | NumberFormatException e) {
            LOG.warn("[Minuteur] Config illisible, valeurs par défaut", e);
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("x", String.valueOf(posX));
        props.setProperty("y", String.valueOf(posY));
        props.setProperty("locked", String.valueOf(locked));
        try (Writer w = Files.newBufferedWriter(configPath())) {
            props.store(w, "TropiFix - minuteur d'evenements");
        } catch (IOException e) {
            LOG.warn("[Minuteur] Impossible d'enregistrer la config", e);
        }
    }
}
