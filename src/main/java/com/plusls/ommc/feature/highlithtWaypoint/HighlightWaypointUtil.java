package com.plusls.ommc.feature.highlithtWaypoint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import com.plusls.ommc.config.Configs;
import com.plusls.ommc.mixin.accessor.AccessorTextComponent;
import com.plusls.ommc.mixin.accessor.AccessorTranslatableComponent;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Option;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// from fabric-voxel map
public class HighlightWaypointUtil {

    private static final String HIGHLIGHT_COMMAND = "highlightWaypoint";
    @Nullable
    public static BlockPos highlightPos;
    public static long lastBeamTime = 0;
    public static Pattern pattern1 = Pattern.compile("\\[(\\w+\\s*:\\s*[-#]?[^\\[\\]]+)(,\\s*\\w+\\s*:\\s*[-#]?[^\\[\\]]+)+]", Pattern.CASE_INSENSITIVE);
    public static Pattern pattern2 = Pattern.compile("\\((\\w+\\s*:\\s*[-#]?[^\\[\\]]+)(,\\s*\\w+\\s*:\\s*[-#]?[^\\[\\]]+)+\\)", Pattern.CASE_INSENSITIVE);
    public static Pattern pattern3 = Pattern.compile("\\[(-?\\d+)(,\\s*-?\\d+)(,\\s*-?\\d+)]", Pattern.CASE_INSENSITIVE);
    public static Pattern pattern4 = Pattern.compile("\\((-?\\d+)(,\\s*-?\\d+)(,\\s*-?\\d+)\\)", Pattern.CASE_INSENSITIVE);
    @Nullable
    public static ResourceKey<Level> currentWorld = null;

    public static void init() {
        ClientCommandManager.DISPATCHER.register(ClientCommandManager.literal(HIGHLIGHT_COMMAND).then(
                ClientCommandManager.argument("x", IntegerArgumentType.integer()).then(
                        ClientCommandManager.argument("y", IntegerArgumentType.integer()).then(
                                ClientCommandManager.argument("z", IntegerArgumentType.integer()).
                                        executes(context -> {
                                            int x = IntegerArgumentType.getInteger(context, "x");
                                            int y = IntegerArgumentType.getInteger(context, "y");
                                            int z = IntegerArgumentType.getInteger(context, "z");
                                            BlockPos pos = new BlockPos(x, y, z);
                                            if (pos.equals(highlightPos)) {
                                                lastBeamTime = System.currentTimeMillis() + 10 * 1000;
                                            } else {
                                                highlightPos = new BlockPos(x, y, z);
                                                lastBeamTime = 0;
                                            }
                                            return 0;
                                        })
                        )
                )
        ));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> currentWorld = Objects.requireNonNull(client.level).dimension());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            currentWorld = null;
            highlightPos = null;
        });
        WorldRenderEvents.END.register(context -> HighlightWaypointUtil.drawWaypoint(context.matrixStack(), context.tickDelta()));
    }

    public static void postRespawn(ClientboundRespawnPacket packet) {
        ResourceKey<Level> newDimension = packet.getDimension();
        if (highlightPos != null && currentWorld != newDimension) {
            if (currentWorld == Level.OVERWORLD && newDimension == Level.NETHER) {
                highlightPos = new BlockPos(highlightPos.getX() / 8, highlightPos.getY(), highlightPos.getZ() / 8);
            } else if (currentWorld == Level.NETHER && newDimension == Level.OVERWORLD) {
                highlightPos = new BlockPos(highlightPos.getX() * 8, highlightPos.getY(), highlightPos.getZ() * 8);
            } else {
                highlightPos = null;
            }
        }
        currentWorld = newDimension;
    }

    public static ArrayList<Tuple<Integer, String>> getWaypointStrings(String message) {
        ArrayList<Tuple<Integer, String>> ret = new ArrayList<>();
        if ((message.contains("[") && message.contains("]")) || (message.contains("(") && message.contains(")"))) {
            getWaypointStringsByPattern(message, ret, pattern1);
            getWaypointStringsByPattern(message, ret, pattern2);
            getWaypointStringsByPattern(message, ret, pattern3);
            getWaypointStringsByPattern(message, ret, pattern4);
        }
        ret.sort(Comparator.comparingInt(Tuple::getA));
        return ret;
    }

    private static void getWaypointStringsByPattern(String message, ArrayList<Tuple<Integer, String>> ret, Pattern pattern) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            String match = matcher.group();
            BlockPos pos = parseWaypoint(match.substring(1, match.length() - 1));
            if (pos == null) {
                continue;
            }
            ret.add(new Tuple<>(matcher.start(), match));
        }
    }

    @Nullable
    private static BlockPos parseWaypoint(String details) {
        String[] pairs = details.split(",");
        Integer x = null;
        Integer z = null;
        int y = 64;
        try {
            for (int i = 0; i < pairs.length; ++i) {
                int splitIndex = pairs[i].indexOf(":");
                String key, value;
                if (splitIndex == -1 && pairs.length == 3) {
                    if (i == 0) {
                        key = "x";
                    } else if (i == 1) {
                        key = "y";
                    } else {
                        key = "z";
                    }
                    value = pairs[i];
                } else {
                    key = pairs[i].substring(0, splitIndex).toLowerCase().trim();
                    value = pairs[i].substring(splitIndex + 1).trim();
                }

                switch (key) {
                    case "x":
                        x = Integer.parseInt(value.replace(" ", ""));
                        break;
                    case "y":
                        y = Integer.parseInt(value.replace(" ", ""));
                        break;
                    case "z":
                        z = Integer.parseInt(value.replace(" ", ""));
                        break;
                }
            }

        } catch (NumberFormatException ignored) {
        }
        if (x == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    public static void parseWaypointText(Component chat) {
        if (chat.getSiblings().size() > 0) {
            for (Component text : chat.getSiblings()) {
                parseWaypointText(text);
            }
        }
        if (chat instanceof TranslatableComponent) {
            Object[] args = ((TranslatableComponent) chat).getArgs();
            boolean updateTranslatableText = false;
            for (int i = 0; i < args.length; ++i) {
                if (args[i] instanceof Component) {
                    parseWaypointText((Component) args[i]);
                } else if (args[i] instanceof String) {
                    Component text = new TextComponent((String) args[i]);
                    if (updateWaypointsText(text)) {
                        args[i] = text;
                        updateTranslatableText = true;
                    }
                }
            }
            if (updateTranslatableText) {
                // refresh cache
                ((AccessorTranslatableComponent) chat).setDecomposedWith(null);
            }
        }
        updateWaypointsText(chat);
    }


    public static boolean updateWaypointsText(Component chat) {
        if (!(chat instanceof TextComponent)) {
            return false;
        }
        TextComponent literalChatText = (TextComponent) chat;

        String message = ((AccessorTextComponent) literalChatText).getText();
        ArrayList<Tuple<Integer, String>> waypointPairs = getWaypointStrings(message);
        if (waypointPairs.size() > 0) {
            Style style = chat.getStyle();
            TextColor color = style.getColor();
            ClickEvent clickEvent = style.getClickEvent();
            if (color == null) {
                color = TextColor.fromLegacyFormat(ChatFormatting.GREEN);
            }
            ArrayList<TextComponent> texts = new ArrayList<>();
            int prevIdx = 0;
            for (Tuple<Integer, String> waypointPair : waypointPairs) {
                String waypointString = waypointPair.getB();
                int waypointIdx = waypointPair.getA();
                TextComponent prevText = new TextComponent(message.substring(prevIdx, waypointIdx));
                prevText.withStyle(style);
                texts.add(prevText);

                TextComponent clickableWaypoint = new TextComponent(waypointString);
                Style chatStyle = clickableWaypoint.getStyle();
                BlockPos pos = Objects.requireNonNull(parseWaypoint(waypointString.substring(1, waypointString.length() - 1)));
                TranslatableComponent hover = new TranslatableComponent("ommc.highlight_waypoint.tooltip");
                if (clickEvent == null || Configs.forceParseWaypointFromChat) {
                    clickEvent = new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            String.format("/%s %d %d %d", HIGHLIGHT_COMMAND, pos.getX(), pos.getY(), pos.getZ()));
                }
                chatStyle = chatStyle.withClickEvent(clickEvent)
                        .withColor(color).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
                clickableWaypoint.withStyle(chatStyle);
                texts.add(clickableWaypoint);
                prevIdx = waypointIdx + waypointString.length();
            }
            if (prevIdx < message.length() - 1) {
                TextComponent lastText = new TextComponent(message.substring(prevIdx));
                lastText.setStyle(style);
                texts.add(lastText);
            }
            for (int i = 0; i < texts.size(); ++i) {
                literalChatText.getSiblings().add(i, texts.get(i));
            }
            ((AccessorTextComponent) literalChatText).setText("");
            literalChatText.withStyle(Style.EMPTY);
            return true;
        }
        return false;
    }

    private static double getDistanceToEntity(Entity entity, BlockPos pos) {
        double dx = pos.getX() + 0.5 - entity.getX();
        double dy = pos.getY() + 0.5 - entity.getY();
        double dz = pos.getZ() + 0.5 - entity.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }


    private static boolean isPointedAt(BlockPos pos, double distance, Entity cameraEntity, float tickDelta) {
        Vec3 cameraPos = cameraEntity.getEyePosition(tickDelta);
        double degrees = 5.0 + Math.min((5.0 / distance), 5.0);
        double angle = degrees * 0.0174533;
        double size = Math.sin(angle) * distance;
        Vec3 cameraPosPlusDirection = cameraEntity.getViewVector(tickDelta);
        Vec3 cameraPosPlusDirectionTimesDistance = cameraPos.add(cameraPosPlusDirection.x() * distance, cameraPosPlusDirection.y() * distance, cameraPosPlusDirection.z() * distance);
        AABB axisalignedbb = new AABB(pos.getX() + 0.5f - size, pos.getY() + 0.5f - size, pos.getZ() + 0.5f - size,
                pos.getX() + 0.5f + size, pos.getY() + 0.5f + size, pos.getZ() + 0.5f + size);
        Optional<Vec3> raycastResult = axisalignedbb.clip(cameraPos, cameraPosPlusDirectionTimesDistance);
        return axisalignedbb.contains(cameraPos) ? distance >= 1.0 : raycastResult.isPresent();
    }

    public static void drawWaypoint(PoseStack matrixStack, float tickDelta) {
        // 多线程可能会出锅？
        BlockPos highlightPos = HighlightWaypointUtil.highlightPos;
        if (highlightPos != null) {
            Minecraft mc = Minecraft.getInstance();
            Entity cameraEntity = Objects.requireNonNull(mc.getCameraEntity());
            // 半透明
            RenderSystem.enableBlend();
            // 允许透过方块渲染
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);

            double distance = getDistanceToEntity(cameraEntity, highlightPos);

            renderLabel(matrixStack, distance, cameraEntity, tickDelta, isPointedAt(highlightPos, distance, cameraEntity, tickDelta), highlightPos);

            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    // code from BeaconBlockEntityRenderer
    @SuppressWarnings("all")
    public static void renderBeam(PoseStack matrices, MultiBufferSource vertexConsumers, ResourceLocation textureId, float tickDelta, float heightScale, long worldTime, int yOffset, int maxY, float[] color, float innerRadius, float outerRadius) {
        int i = yOffset + maxY;
        matrices.pushPose();
        matrices.translate(0.5D, 0.0D, 0.5D);
        float f = (float) Math.floorMod(worldTime, 40) + tickDelta;
        float g = maxY < 0 ? f : -f;
        float h = Mth.frac(g * 0.2F - (float) Mth.floor(g * 0.1F));
        float j = color[0];
        float k = color[1];
        float l = color[2];
        matrices.pushPose();
        matrices.mulPose(Vector3f.YP.rotationDegrees(f * 2.25F - 45.0F));
        float y = 0.0F;
        float ab = 0.0F;
        float ac = -innerRadius;
        float r = 0.0F;
        float s = 0.0F;
        float t = -innerRadius;
        float ag = 0.0F;
        float ah = 1.0F;
        float ai = -1.0F + h;
        float aj = (float) maxY * heightScale * (0.5F / innerRadius) + ai;
        // Change layer to getTextSeeThrough
        // it works, but why?
        renderBeamLayer(matrices, vertexConsumers.getBuffer(RenderType.textSeeThrough(textureId)), j, k, l, 1.0F, yOffset, i, 0.0F, innerRadius, innerRadius, 0.0F, ac, 0.0F, 0.0F, t, 0.0F, 1.0F, aj, ai);
        matrices.popPose();
        y = -outerRadius;
        float z = -outerRadius;
        ab = -outerRadius;
        ac = -outerRadius;
        ag = 0.0F;
        ah = 1.0F;
        ai = -1.0F + h;
        aj = (float) maxY * heightScale + ai;
        renderBeamLayer(matrices, vertexConsumers.getBuffer(RenderType.beaconBeam(textureId, true)), j, k, l, 0.125F, yOffset, i, y, z, outerRadius, ab, ac, outerRadius, outerRadius, outerRadius, 0.0F, 1.0F, aj, ai);
        matrices.popPose();
    }

    private static void renderBeamFace(Matrix4f modelMatrix, Matrix3f normalMatrix, VertexConsumer vertices, float red, float green, float blue, float alpha, int yOffset, int height, float x1, float z1, float x2, float z2, float u1, float u2, float v1, float v2) {
        renderBeamVertex(modelMatrix, normalMatrix, vertices, red, green, blue, alpha, height, x1, z1, u2, v1);
        renderBeamVertex(modelMatrix, normalMatrix, vertices, red, green, blue, alpha, yOffset, x1, z1, u2, v2);
        renderBeamVertex(modelMatrix, normalMatrix, vertices, red, green, blue, alpha, yOffset, x2, z2, u1, v2);
        renderBeamVertex(modelMatrix, normalMatrix, vertices, red, green, blue, alpha, height, x2, z2, u1, v1);
    }

    private static void renderBeamVertex(Matrix4f modelMatrix, Matrix3f normalMatrix, VertexConsumer vertices, float red, float green, float blue, float alpha, int y, float x, float z, float u, float v) {
        vertices.vertex(modelMatrix, x, (float) y, z).color(red, green, blue, alpha).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0.0F, 1.0F, 0.0F).endVertex();
    }

    @SuppressWarnings("all")
    private static void renderBeamLayer(PoseStack matrices, VertexConsumer vertices, float red, float green, float blue, float alpha, int yOffset, int height, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float u1, float u2, float v1, float v2) {
        PoseStack.Pose entry = matrices.last();
        Matrix4f matrix4f = entry.pose();
        Matrix3f matrix3f = entry.normal();
        renderBeamFace(matrix4f, matrix3f, vertices, red, green, blue, alpha, yOffset, height, x1, z1, x2, z2, u1, u2, v1, v2);
        renderBeamFace(matrix4f, matrix3f, vertices, red, green, blue, alpha, yOffset, height, x4, z4, x3, z3, u1, u2, v1, v2);
        renderBeamFace(matrix4f, matrix3f, vertices, red, green, blue, alpha, yOffset, height, x2, z2, x4, z4, u1, u2, v1, v2);
        renderBeamFace(matrix4f, matrix3f, vertices, red, green, blue, alpha, yOffset, height, x3, z3, x1, z1, u1, u2, v1, v2);
    }

    public static void renderLabel(PoseStack matrixStack, double distance, Entity cameraEntity, float tickDelta, boolean isPointedAt, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();

        String name = String.format("x:%d, y:%d, z:%d (%dm)", pos.getX(), pos.getY(), pos.getZ(), (int) distance);
        double baseX = pos.getX() - Mth.lerp(tickDelta, cameraEntity.xo, cameraEntity.getX());
        double baseY = pos.getY() - Mth.lerp(tickDelta, cameraEntity.yo, cameraEntity.getY()) - 1.5;
        double baseZ = pos.getZ() - Mth.lerp(tickDelta, cameraEntity.zo, cameraEntity.getZ());
        // 当前渲染的最大距离
        double maxDistance = Option.RENDER_DISTANCE.get(mc.options) * 16;
        double adjustedDistance = distance;
        if (distance > maxDistance) {
            baseX = baseX / distance * maxDistance;
            baseY = baseY / distance * maxDistance;
            baseZ = baseZ / distance * maxDistance;
            adjustedDistance = maxDistance;
        }
        // 根据调节后的距离决定绘制的大小
        float scale = (float) (adjustedDistance * 0.1f + 1.0f) * 0.0266f;
        matrixStack.pushPose();
        // 当前绘制位置是以玩家为中心的，转移到目的地
        matrixStack.translate(baseX, baseY, baseZ);

        if (lastBeamTime >= System.currentTimeMillis()) {
            // 画信标光柱
            MultiBufferSource.BufferSource vertexConsumerProvider0 = mc.renderBuffers().crumblingBufferSource();
            float[] color = {1.0f, 0.0f, 0.0f};
            renderBeam(matrixStack, vertexConsumerProvider0, BeaconRenderer.BEAM_LOCATION,
                    tickDelta, 1.0f, Objects.requireNonNull(mc.level).getGameTime(), (int) (baseY - 512), 1024, color, 0.2F, 0.25F);
            vertexConsumerProvider0.endBatch();

            // 画完后会关闭半透明，需要手动打开
            RenderSystem.enableBlend();
        }

        // 移动到方块中心
        matrixStack.translate(0.5f, 0.5f, 0.5f);

        // 在玩家正对着的平面进行绘制
        matrixStack.mulPose(Vector3f.YP.rotationDegrees(-cameraEntity.getYRot()));
        matrixStack.mulPose(Vector3f.XP.rotationDegrees(mc.getEntityRenderDispatcher().camera.getXRot()));
        // 缩放绘制的大小，让 waypoint 根据距离缩放
        matrixStack.scale(-scale, -scale, -scale);
        Matrix4f matrix4f = matrixStack.last().pose();
        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder vertexBuffer = tessellator.getBuilder();
        // 透明度
        float fade = distance < 5.0 ? 1.0f : (float) distance / 5.0f;
        fade = Math.min(fade, 1.0f);
        // 渲染的图标的大小
        float xWidth = 10.0f;
        float yWidth = 10.0f;
        // 绿色
        float iconR = 1.0f;
        float iconG = 0.0f;
        float iconB = 0.0f;
        float textFieldR = 3.0f;
        float textFieldG = 0.0f;
        float textFieldB = 0.0f;
        // 图标
        TextureAtlasSprite icon = HighlightWaypointResourceLoader.targetIdSprite;
        // 不设置渲染不出
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);

        // 渲染图标
        RenderSystem.enableTexture();
        vertexBuffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertexBuffer.vertex(matrix4f, -xWidth, -yWidth, 0.0f).uv(icon.getU0(), icon.getV0()).color(iconR, iconG, iconB, fade).endVertex();
        vertexBuffer.vertex(matrix4f, -xWidth, yWidth, 0.0f).uv(icon.getU0(), icon.getV1()).color(iconR, iconG, iconB, fade).endVertex();
        vertexBuffer.vertex(matrix4f, xWidth, yWidth, 0.0f).uv(icon.getU1(), icon.getV1()).color(iconR, iconG, iconB, fade).endVertex();
        vertexBuffer.vertex(matrix4f, xWidth, -yWidth, 0.0f).uv(icon.getU1(), icon.getV0()).color(iconR, iconG, iconB, fade).endVertex();
        tessellator.end();
        RenderSystem.disableTexture();

        Font textRenderer = mc.font;
        if (isPointedAt && textRenderer != null) {
            // 渲染高度
            int elevateBy = -19;
            RenderSystem.enablePolygonOffset();
            int halfStringWidth = textRenderer.width(name) / 2;
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            // 渲染内框
            RenderSystem.polygonOffset(1.0f, 11.0f);
            vertexBuffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            vertexBuffer.vertex(matrix4f, -halfStringWidth - 2, -2 + elevateBy, 0.0f).color(textFieldR, textFieldG, textFieldB, 0.6f * fade).endVertex();
            vertexBuffer.vertex(matrix4f, -halfStringWidth - 2, 9 + elevateBy, 0.0f).color(textFieldR, textFieldG, textFieldB, 0.6f * fade).endVertex();
            vertexBuffer.vertex(matrix4f, halfStringWidth + 2, 9 + elevateBy, 0.0f).color(textFieldR, textFieldG, textFieldB, 0.6f * fade).endVertex();
            vertexBuffer.vertex(matrix4f, halfStringWidth + 2, -2 + elevateBy, 0.0f).color(textFieldR, textFieldG, textFieldB, 0.6f * fade).endVertex();
            tessellator.end();

            // 渲染外框
            RenderSystem.polygonOffset(1.0f, 9.0f);
            vertexBuffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            vertexBuffer.vertex(matrix4f, -halfStringWidth - 1, -1 + elevateBy, 0.0f).color(0.0f, 0.0f, 0.0f, 0.15f * fade).endVertex();
            vertexBuffer.vertex(matrix4f, -halfStringWidth - 1, 8 + elevateBy, 0.0f).color(0.0f, 0.0f, 0.0f, 0.15f * fade).endVertex();
            vertexBuffer.vertex(matrix4f, halfStringWidth + 1, 8 + elevateBy, 0.0f).color(0.0f, 0.0f, 0.0f, 0.15f * fade).endVertex();
            vertexBuffer.vertex(matrix4f, halfStringWidth + 1, -1 + elevateBy, 0.0f).color(0.0f, 0.0f, 0.0f, 0.15f * fade).endVertex();
            tessellator.end();
            RenderSystem.disablePolygonOffset();

            // 渲染文字
            RenderSystem.enableTexture();
            MultiBufferSource.BufferSource vertexConsumerProvider = mc.renderBuffers().crumblingBufferSource();
            int textColor = (int) (255.0f * fade) << 24 | 0xCCCCCC;
            RenderSystem.disableDepthTest();
            textRenderer.drawInBatch(new TextComponent(name), (float) (-textRenderer.width(name) / 2), elevateBy, textColor, false, matrix4f, vertexConsumerProvider, true, 0, 0xF000F0);
            vertexConsumerProvider.endBatch();
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        matrixStack.popPose();
    }
}
