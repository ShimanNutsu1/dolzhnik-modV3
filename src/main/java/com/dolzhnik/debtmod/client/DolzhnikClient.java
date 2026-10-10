package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.DebtMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class DolzhnikClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(CreditorModel.LAYER, CreditorModel::getTexturedModelData);
        EntityRendererRegistry.register(DebtMod.CREDITOR, CreditorRenderer::new);

        EntityModelLayerRegistry.registerModelLayer(DebtorModels.FOREST, DebtorModels::forest);
        EntityModelLayerRegistry.registerModelLayer(DebtorModels.DEPTHS, DebtorModels::depths);
        EntityModelLayerRegistry.registerModelLayer(DebtorModels.FIRE, DebtorModels::fire);
        EntityModelLayerRegistry.registerModelLayer(DebtorModels.GHOST, DebtorModels::ghost);

        // Лес: высокая фигура, корни шевелятся медленно. Масштаб 1.35: модель 2 блока, хитбокс 2.7.
        EntityRendererRegistry.register(DebtMod.FOREST_DEBTOR, ctx -> new DebtorRenderer<>(ctx,
                DebtorModels.FOREST, DebtorModels.FOREST_WAVES, -0.05f, 0.07f,
                "forest_debtor", "forest_debtor_glow", 1.35f, 0.7f, false));
        // Недра: хитбокс 2.4, масштаб 1.2.
        EntityRendererRegistry.register(DebtMod.DEPTHS_DEBTOR, ctx -> new DebtorRenderer<>(ctx,
                DebtorModels.DEPTHS, 0, -0.1f, 0.1f,
                "depths_debtor", "depths_debtor_glow", 1.2f, 0.9f, false));
        // Огонь: светится сам, пламя трепещет быстро.
        EntityRendererRegistry.register(DebtMod.FIRE_DEBTOR, ctx -> new DebtorRenderer<>(ctx,
                DebtorModels.FIRE, DebtorModels.FIRE_WAVES, -0.1f, 0.35f,
                "fire_debtor", "fire_debtor_glow", 1.0f, 0.5f, true));
        EntityRendererRegistry.register(DebtMod.GHOST, GhostRenderer::new);
    }
}
