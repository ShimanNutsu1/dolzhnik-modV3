package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.ManifestationEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Общий рендерер для лесного, подземного и огненного взыскателей. */
public class DebtorRenderer<T extends ManifestationEntity> extends MobEntityRenderer<T, PartsModel<T>> {
    private final Identifier texture;
    private final float scale;
    private final boolean fullbright;

    public DebtorRenderer(EntityRendererFactory.Context ctx, EntityModelLayer layer, int waves, float armPitch, float waveSpeed,
                          String textureName, String glowName, float scale, float shadow, boolean fullbright) {
        super(ctx, new PartsModel<>(ctx.getPart(layer), waves, armPitch, waveSpeed), shadow);
        this.texture = new Identifier("dolzhnik", "textures/entity/" + textureName + ".png");
        this.scale = scale;
        this.fullbright = fullbright;
        if (glowName != null) {
            this.addFeature(new GlowFeature<>(this, new Identifier("dolzhnik", "textures/entity/" + glowName + ".png")));
        }
    }

    @Override
    protected void scale(T entity, MatrixStack matrices, float amount) {
        matrices.scale(scale, scale, scale);
    }

    @Override
    protected int getBlockLight(T entity, BlockPos pos) {
        return fullbright ? 15 : super.getBlockLight(entity, pos);
    }

    @Override
    public Identifier getTexture(T entity) {
        return texture;
    }
}
