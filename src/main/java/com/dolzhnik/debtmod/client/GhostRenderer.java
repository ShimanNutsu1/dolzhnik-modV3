package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.GhostEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** Полупрозрачный бледный призрак, размер берётся у убитого существа. */
public class GhostRenderer extends MobEntityRenderer<GhostEntity, GhostModel> {
    private static final Identifier TEXTURE = new Identifier("dolzhnik", "textures/entity/debt_ghost.png");

    public GhostRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new GhostModel(ctx.getPart(DebtorModels.GHOST)), 0.0f);
    }

    @Override
    protected void scale(GhostEntity e, MatrixStack matrices, float amount) {
        float s;
        if (e.isBiped()) s = MathHelper.clamp(e.ghostHeight() / 2.0f, 0.5f, 3.0f);   // модель двуногого: 2 блока
        else s = MathHelper.clamp(Math.max(e.ghostHeight(), e.ghostWidth()), 0.4f, 3.0f); // четвероногого: 1 блок
        matrices.scale(s, s, s);
    }

    @Override
    protected RenderLayer getRenderLayer(GhostEntity entity, boolean showBody, boolean translucent, boolean showOutline) {
        return RenderLayer.getEntityTranslucent(getTexture(entity));
    }

    @Override
    protected int getBlockLight(GhostEntity entity, BlockPos pos) {
        return 11;
    }

    @Override
    public Identifier getTexture(GhostEntity entity) {
        return TEXTURE;
    }
}
