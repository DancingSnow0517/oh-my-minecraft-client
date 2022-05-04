package com.plusls.ommc.mixin.feature.worldEaterMineHelper.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.plusls.ommc.feature.worldEaterMineHelper.WorldEaterMineHelperUtil;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

//#if MC > 11802
//$$ import net.minecraft.util.RandomSource;
//#endif

//#if MC > 11404
import com.mojang.blaze3d.vertex.VertexConsumer;
//#else
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//#endif

@Mixin(value = BlockRenderContext.class, remap = false)
public abstract class MixinBlockRenderContext implements RenderContext {

    @Shadow
    @Final
    private BlockRenderInfo blockInfo;

    @Inject(
            //#if MC > 11404
            method = "render",
            //#else
            //$$ method = "tesselate",
            //#endif
            at = @At(value = "INVOKE",
                    target = "Lnet/fabricmc/fabric/api/renderer/v1/model/FabricBakedModel;emitBlockQuads(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Ljava/util/function/Supplier;Lnet/fabricmc/fabric/api/renderer/v1/render/RenderContext;)V",
                    shift = At.Shift.AFTER, ordinal = 0, remap = true))
    private void emitCustomBlockQuads(BlockAndTintGetter blockView, BakedModel model,
                                      BlockState state, BlockPos pos,
                                      //#if MC > 11404
                                      PoseStack matrixStack,
                                      VertexConsumer buffer,
                                      //#else
                                      //$$ BufferBuilder buffer,
                                      //#endif
                                      //#if MC > 11802
                                      //$$ RandomSource random,
                                      //#elseif MC > 11404
                                      Random random,
                                      //#endif
                                      long seed,
                                      //#if MC > 11404
                                      int overlay,
                                      //#endif
                                      CallbackInfoReturnable<Boolean> cir) {
        WorldEaterMineHelperUtil.emitCustomBlockQuads(blockView, state, pos, this.blockInfo.randomSupplier, this);
    }
}
