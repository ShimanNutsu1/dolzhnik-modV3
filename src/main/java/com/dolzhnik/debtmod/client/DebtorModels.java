package com.dolzhnik.debtmod.client;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

/** Геометрия существ долга. Все текстуры 64x64, начало ног на y=24 (стандарт Minecraft). */
public final class DebtorModels {
    private DebtorModels() {}

    public static final EntityModelLayer FOREST = layer("forest_debtor");
    public static final EntityModelLayer DEPTHS = layer("depths_debtor");
    public static final EntityModelLayer FIRE = layer("fire_debtor");
    public static final EntityModelLayer GHOST = layer("debt_ghost");

    public static final int FOREST_WAVES = 6;
    public static final int FIRE_WAVES = 5;

    private static EntityModelLayer layer(String name) {
        return new EntityModelLayer(new Identifier("dolzhnik", name), "main");
    }

    /** Высокая фигура из чёрной коры: ветви-рога на голове и шесть корней вместо подола. */
    public static TexturedModelData forest() {
        ModelData data = new ModelData();
        ModelPartData r = data.getRoot();
        r.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -8f, -4f, 8f, 8f, 8f), ModelTransform.pivot(0f, 0f, 0f));
        // Ветви на голове (неподвижные).
        r.addChild("branch0", ModelPartBuilder.create().uv(0, 32).cuboid(-1f, -9f, -1f, 2f, 9f, 2f), ModelTransform.of(-2.5f, -7f, 0f, 0f, 0f, -0.45f));
        r.addChild("branch1", ModelPartBuilder.create().uv(0, 32).cuboid(-1f, -9f, -1f, 2f, 9f, 2f), ModelTransform.of(2.5f, -7f, 0f, 0f, 0f, 0.45f));
        r.addChild("body", ModelPartBuilder.create().uv(16, 16).cuboid(-4f, 0f, -2f, 8f, 14f, 4f), ModelTransform.pivot(0f, 0f, 0f));
        r.addChild("right_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -1f, 2f, 22f, 2f), ModelTransform.pivot(-5f, 2f, 0f));
        r.addChild("left_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -1f, 2f, 22f, 2f), ModelTransform.pivot(5f, 2f, 0f));
        r.addChild("right_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-1.5f, 0f, -1.5f, 3f, 10f, 3f), ModelTransform.pivot(-2f, 14f, 0f));
        r.addChild("left_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-1.5f, 0f, -1.5f, 3f, 10f, 3f), ModelTransform.pivot(2f, 14f, 0f));
        // Корни растекаются от пояса к земле.
        for (int i = 0; i < FOREST_WAVES; i++) {
            float ang = (float) (Math.PI * 2 * i / FOREST_WAVES);
            float x = (float) Math.cos(ang) * 3f;
            float z = (float) Math.sin(ang) * 3f;
            r.addChild("w" + i, ModelPartBuilder.create().uv(0, 32).cuboid(-1f, 0f, -1f, 2f, 10f, 2f),
                    ModelTransform.of(x, 14f, z, (float) Math.sin(ang) * 0.5f, 0f, -(float) Math.cos(ang) * 0.5f));
        }
        return TexturedModelData.of(data, 64, 64);
    }

    /** Приземистый тяжёлый великан из породы: огромные руки, горб из камней. */
    public static TexturedModelData depths() {
        ModelData data = new ModelData();
        ModelPartData r = data.getRoot();
        r.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -8f, -4f, 8f, 8f, 8f), ModelTransform.pivot(0f, 3f, -1f));
        r.addChild("body", ModelPartBuilder.create().uv(16, 16).cuboid(-7f, 0f, -4f, 14f, 14f, 8f), ModelTransform.pivot(0f, 3f, 0f));
        r.addChild("rock0", ModelPartBuilder.create().uv(0, 32).cuboid(-3f, -3f, 0f, 6f, 6f, 5f), ModelTransform.of(-3f, 6f, 4f, 0.2f, 0.3f, 0.2f));
        r.addChild("rock1", ModelPartBuilder.create().uv(0, 32).cuboid(-2f, -2f, 0f, 5f, 5f, 5f), ModelTransform.of(3f, 10f, 4f, -0.2f, -0.3f, 0.1f));
        r.addChild("right_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-3f, -2f, -3f, 6f, 20f, 6f), ModelTransform.pivot(-10f, 5f, 0f));
        r.addChild("left_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-3f, -2f, -3f, 6f, 20f, 6f), ModelTransform.pivot(10f, 5f, 0f));
        r.addChild("right_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-3f, 0f, -3f, 6f, 9f, 6f), ModelTransform.pivot(-4f, 15f, 0f));
        r.addChild("left_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-3f, 0f, -3f, 6f, 9f, 6f), ModelTransform.pivot(4f, 15f, 0f));
        return TexturedModelData.of(data, 64, 64);
    }

    /** Тонкая обугленная фигура с языками пламени над головой и плечами. */
    public static TexturedModelData fire() {
        ModelData data = new ModelData();
        ModelPartData r = data.getRoot();
        r.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -8f, -4f, 8f, 8f, 8f), ModelTransform.pivot(0f, 0f, 0f));
        r.addChild("body", ModelPartBuilder.create().uv(16, 16).cuboid(-3f, 0f, -2f, 6f, 12f, 4f), ModelTransform.pivot(0f, 0f, 0f));
        r.addChild("right_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -1f, 2f, 18f, 2f), ModelTransform.pivot(-4.5f, 2f, 0f));
        r.addChild("left_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -1f, 2f, 18f, 2f), ModelTransform.pivot(4.5f, 2f, 0f));
        r.addChild("right_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-1.5f, 0f, -1.5f, 3f, 12f, 3f), ModelTransform.pivot(-1.8f, 12f, 0f));
        r.addChild("left_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-1.5f, 0f, -1.5f, 3f, 12f, 3f), ModelTransform.pivot(1.8f, 12f, 0f));
        float[] xs = {-2.5f, 0f, 2.5f, -4f, 4f};
        float[] ys = {-8f, -9f, -8f, 1f, 1f};
        for (int i = 0; i < FIRE_WAVES; i++) {
            r.addChild("w" + i, ModelPartBuilder.create().uv(0, 32).cuboid(-1f, -8f, -0.5f, 2f, 8f, 1f),
                    ModelTransform.of(xs[i], ys[i], 0f, 0f, 0f, (i - 2) * 0.22f));
        }
        return TexturedModelData.of(data, 64, 64);
    }

    /** Призрак: в модели обе фигуры (двуногая и четвероногая), нужная показывается по пропорциям убитого. */
    public static TexturedModelData ghost() {
        ModelData data = new ModelData();
        ModelPartData r = data.getRoot();
        // Двуногая.
        r.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -8f, -4f, 8f, 8f, 8f), ModelTransform.pivot(0f, 0f, 0f));
        r.addChild("body", ModelPartBuilder.create().uv(16, 16).cuboid(-4f, 0f, -2f, 8f, 12f, 4f), ModelTransform.pivot(0f, 0f, 0f));
        r.addChild("right_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-3f, -2f, -2f, 4f, 12f, 4f), ModelTransform.pivot(-5f, 2f, 0f));
        r.addChild("left_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -2f, 4f, 12f, 4f), ModelTransform.pivot(5f, 2f, 0f));
        r.addChild("right_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-2f, 0f, -2f, 4f, 12f, 4f), ModelTransform.pivot(-1.9f, 12f, 0f));
        r.addChild("left_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-2f, 0f, -2f, 4f, 12f, 4f), ModelTransform.pivot(1.9f, 12f, 0f));
        // Четвероногая (как у свиньи: тело лежит горизонтально).
        r.addChild("q_head", ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -4f, -8f, 8f, 8f, 8f), ModelTransform.pivot(0f, 12f, -6f));
        r.addChild("q_body", ModelPartBuilder.create().uv(16, 16).cuboid(-5f, -10f, -7f, 10f, 16f, 8f),
                ModelTransform.of(0f, 11f, 2f, (float) (Math.PI / 2), 0f, 0f));
        r.addChild("q_leg0", ModelPartBuilder.create().uv(0, 16).cuboid(-2f, 0f, -2f, 4f, 6f, 4f), ModelTransform.pivot(-3f, 18f, 7f));
        r.addChild("q_leg1", ModelPartBuilder.create().uv(0, 16).cuboid(-2f, 0f, -2f, 4f, 6f, 4f), ModelTransform.pivot(3f, 18f, 7f));
        r.addChild("q_leg2", ModelPartBuilder.create().uv(0, 16).cuboid(-2f, 0f, -2f, 4f, 6f, 4f), ModelTransform.pivot(-3f, 18f, -5f));
        r.addChild("q_leg3", ModelPartBuilder.create().uv(0, 16).cuboid(-2f, 0f, -2f, 4f, 6f, 4f), ModelTransform.pivot(3f, 18f, -5f));
        return TexturedModelData.of(data, 64, 64);
    }
}
