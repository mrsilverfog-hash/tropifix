package com.tropimon.tropifix.pasture;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Détection des blocs de pâturage Cobblemon (cobblemon:pasture) : contour + faisceau turquoise.
 *
 * Désactivée à chaque lancement du jeu ; la touche « Pâturages : activer / désactiver »
 * (Options > Contrôles, catégorie TropiFix, non assignée par défaut) l'allume ou l'éteint.
 * Le pâturage fait 2 blocs de haut : seul le bloc du bas (part=bottom) est suivi, pour un seul
 * faisceau par pâturage.
 *
 * Scan par sections de chunk (16x16x16) réparti sur les ticks, hors du rendu : une section dont
 * la palette ne contient pas de pâturage est sautée d'un coup.
 */
public final class PastureBeacon {

    private static final Identifier PASTURE_ID = Identifier.of("cobblemon", "pasture");

    // Faisceau turquoise
    private static final float R = 0.1f;
    private static final float G = 0.9f;
    private static final float B = 0.8f;
    private static final int BEAM_HEIGHT = 256;

    // Portée horizontale : 6 chunks = 96 blocs ; verticale : toute la hauteur du monde
    private static final int SCAN_RADIUS_CHUNKS = 6;
    private static final int SECTION_BUDGET_PER_TICK = 1500;
    private static final int BLOCK_BUDGET_PER_TICK = 40000;
    private static final int MIN_TICKS_BETWEEN_PASSES = 20;

    private static boolean enabled = false; // désactivée de base
    private static KeyBinding toggleKey;
    private static Block pastureBlock = null;
    private static boolean pastureResolved = false;

    private static volatile List<BlockPos> found = new ArrayList<>();

    // Passe de scan en cours
    private static boolean scanning = false;
    private static int[] chunkXs = new int[0];
    private static int[] chunkZs = new int[0];
    private static int chunkIdx = 0;
    private static int sectionIdx = 0;
    private static List<BlockPos> pending = new ArrayList<>();
    private static int ticksSincePass = MIN_TICKS_BETWEEN_PASSES;
    private static World lastWorld = null;

    private PastureBeacon() {}

    public static void register() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Pâturages : activer / désactiver", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "TropiFix"));

        ClientTickEvents.END_CLIENT_TICK.register(PastureBeacon::tick);
        WorldRenderEvents.LAST.register(PastureBeacon::render);
    }

    private static Block pasture() {
        if (!pastureResolved) {
            pastureResolved = true;
            Block b = Registries.BLOCK.get(PASTURE_ID);
            pastureBlock = (b == Blocks.AIR) ? null : b;
        }
        return pastureBlock;
    }

    private static void reset() {
        found = new ArrayList<>();
        scanning = false;
        ticksSincePass = MIN_TICKS_BETWEEN_PASSES;
    }

    // ------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            reset();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(enabled
                    ? "§bPâturages : détection §aactivée"
                    : "§bPâturages : détection §cdésactivée"), true);
            }
        }
        if (!enabled) return;

        World world = client.world;
        if (world == null || client.player == null) return;
        if (world != lastWorld) {
            lastWorld = world;
            reset();
        }
        Block target = pasture();
        if (target == null) return;

        if (!scanning) {
            if (++ticksSincePass < MIN_TICKS_BETWEEN_PASSES) return;
            startPass(client);
        }

        int sections = 0;
        int blocks = 0;
        while (scanning && sections < SECTION_BUDGET_PER_TICK && blocks < BLOCK_BUDGET_PER_TICK) {
            if (chunkIdx >= chunkXs.length) {
                found = pending;
                scanning = false;
                ticksSincePass = 0;
                break;
            }
            int cx = chunkXs[chunkIdx];
            int cz = chunkZs[chunkIdx];
            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) {
                chunkIdx++;
                sectionIdx = 0;
                continue;
            }
            ChunkSection[] secs = chunk.getSectionArray();
            if (sectionIdx >= secs.length) {
                chunkIdx++;
                sectionIdx = 0;
                continue;
            }
            int si = sectionIdx++;
            sections++;
            ChunkSection sec = secs[si];
            if (sec == null || sec.isEmpty()) continue;
            if (!sec.getBlockStateContainer().hasAny(st -> st.getBlock() == target)) continue;

            int baseX = cx << 4;
            int baseY = chunk.sectionIndexToCoord(si) << 4;
            int baseZ = cz << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (st.getBlock() == target && isBottom(st)) {
                            pending.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
            blocks += 4096;
        }
    }

    /** Bas du pâturage (part=bottom) ; si la propriété n'existe pas, on garde le bloc. */
    private static boolean isBottom(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals("part")) {
                return String.valueOf(state.get(prop)).equalsIgnoreCase("bottom");
            }
        }
        return true;
    }

    private static void startPass(MinecraftClient client) {
        int pcx = client.player.getBlockX() >> 4;
        int pcz = client.player.getBlockZ() >> 4;
        int r = SCAN_RADIUS_CHUNKS;
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                chunks.add(new int[]{pcx + dx, pcz + dz, dx * dx + dz * dz});
            }
        }
        chunks.sort((p, q) -> Integer.compare(p[2], q[2]));
        chunkXs = new int[chunks.size()];
        chunkZs = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            chunkXs[i] = chunks.get(i)[0];
            chunkZs[i] = chunks.get(i)[1];
        }
        chunkIdx = 0;
        sectionIdx = 0;
        pending = new ArrayList<>();
        scanning = true;
    }

    // ------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (!enabled) return;
        List<BlockPos> list = found;
        if (list.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        Block target = pasture();
        if (target == null) return;

        Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();
        Vec3d camPos = context.camera().getPos();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();

        List<BlockPos> visible = new ArrayList<>();
        for (BlockPos pos : list) {
            if (client.world.getBlockState(pos).getBlock() != target) continue; // cassé depuis le scan
            context.matrixStack().push();
            context.matrixStack().translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            // Contour sur toute la hauteur du pâturage (2 blocs)
            WorldRenderer.drawBox(context.matrixStack(), lines, 0, 0, 0, 1, 2, 1, R, G, B, 1.0f);
            context.matrixStack().pop();
            visible.add(pos);
        }

        if (!visible.isEmpty()) {
            Tessellator tessellator = Tessellator.getInstance();
            drawBeams(tessellator, viewMatrix, camPos, visible, 0.4f, 0.6f, 0.4f);
            drawBeams(tessellator, viewMatrix, camPos, visible, 0.3f, 0.7f, 0.15f);
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void drawBeams(Tessellator tessellator, Matrix4f view, Vec3d cam, List<BlockPos> positions,
                                  float min, float max, float alpha) {
        BufferBuilder b = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (BlockPos pos : positions) {
            Matrix4f m = new Matrix4f(view);
            m.translate((float) (pos.getX() - cam.x), (float) (pos.getY() + 2.0 - cam.y), (float) (pos.getZ() - cam.z));
            face(b, m, min, max, alpha);
        }
        BuiltBuffer built = b.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
    }

    private static void face(BufferBuilder b, Matrix4f m, float min, float max, float a) {
        float h = BEAM_HEIGHT;
        b.vertex(m, min, 0, min).color(R, G, B, a); b.vertex(m, max, 0, min).color(R, G, B, a);
        b.vertex(m, max, h, min).color(R, G, B, a); b.vertex(m, min, h, min).color(R, G, B, a);
        b.vertex(m, min, 0, max).color(R, G, B, a); b.vertex(m, max, 0, max).color(R, G, B, a);
        b.vertex(m, max, h, max).color(R, G, B, a); b.vertex(m, min, h, max).color(R, G, B, a);
        b.vertex(m, max, 0, min).color(R, G, B, a); b.vertex(m, max, 0, max).color(R, G, B, a);
        b.vertex(m, max, h, max).color(R, G, B, a); b.vertex(m, max, h, min).color(R, G, B, a);
        b.vertex(m, min, 0, min).color(R, G, B, a); b.vertex(m, min, 0, max).color(R, G, B, a);
        b.vertex(m, min, h, max).color(R, G, B, a); b.vertex(m, min, h, min).color(R, G, B, a);
    }
}
