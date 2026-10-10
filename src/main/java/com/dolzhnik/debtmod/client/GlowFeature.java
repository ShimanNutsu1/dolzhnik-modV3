package com.dolzhnik.debtmod.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

/** Светящийся слой: рисуется на полной яркости поверх основной текстуры (глаза, тлеющие трещины). */
public class GlowFeature<T extends LivingEntity, M extends EntityModel<T>> extends EyesFeatureRenderer<T, M> {
    private final RenderLayer layer;

    public GlowFeature(FeatureRendererContext<T, M> ctx, Identifier texture) {
        super(ctx);
        this.layer = RenderLayer.getEyes(texture);
    }

    @Override
    public RenderLayer getEyesTexture() {
        return layer;
    }
}
