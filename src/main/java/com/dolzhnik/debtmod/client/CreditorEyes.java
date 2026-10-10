package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.CreditorEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.util.Identifier;

/** Красные глаза, которые светятся в темноте. */
public class CreditorEyes extends EyesFeatureRenderer<CreditorEntity, CreditorModel> {
    private static final RenderLayer EYES = RenderLayer.getEyes(new Identifier("dolzhnik", "textures/entity/creditor_eyes.png"));

    public CreditorEyes(FeatureRendererContext<CreditorEntity, CreditorModel> ctx) {
        super(ctx);
    }

    @Override
    public RenderLayer getEyesTexture() {
        return EYES;
    }
}
