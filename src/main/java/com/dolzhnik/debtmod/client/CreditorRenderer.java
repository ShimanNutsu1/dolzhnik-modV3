package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.CreditorEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.util.Identifier;

public class CreditorRenderer extends MobEntityRenderer<CreditorEntity, CreditorModel> {
    private static final Identifier TEXTURE = new Identifier("dolzhnik", "textures/entity/creditor.png");

    public CreditorRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new CreditorModel(ctx.getPart(CreditorModel.LAYER)), 0.5f);
        this.addFeature(new CreditorEyes(this));
    }

    @Override
    public Identifier getTexture(CreditorEntity entity) {
        return TEXTURE;
    }
}
