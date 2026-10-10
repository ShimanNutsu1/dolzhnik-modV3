package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.CreditorEntity;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Высокая худая фигура с длинными руками и шестью щупальцами за спиной. */
public class CreditorModel extends EntityModel<CreditorEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(new Identifier("dolzhnik", "creditor"), "main");
    private static final int TENTACLES = 6;

    private final ModelPart root, head, rightArm, leftArm, rightLeg, leftLeg;
    private final ModelPart[] tentacles = new ModelPart[TENTACLES];

    public CreditorModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        for (int i = 0; i < TENTACLES; i++) tentacles[i] = root.getChild("tentacle" + i);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData r = data.getRoot();
        r.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -8f, -4f, 8f, 8f, 8f), ModelTransform.pivot(0f, 0f, 0f));
        r.addChild("body", ModelPartBuilder.create().uv(16, 16).cuboid(-4f, 0f, -2f, 8f, 12f, 4f), ModelTransform.pivot(0f, 0f, 0f));
        // Руки до самых колен и ниже.
        r.addChild("right_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -1f, 2f, 22f, 2f), ModelTransform.pivot(-5f, 2f, 0f));
        r.addChild("left_arm", ModelPartBuilder.create().uv(40, 16).cuboid(-1f, -2f, -1f, 2f, 22f, 2f), ModelTransform.pivot(5f, 2f, 0f));
        r.addChild("right_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-1.5f, 0f, -1.5f, 3f, 12f, 3f), ModelTransform.pivot(-2f, 12f, 0f));
        r.addChild("left_leg", ModelPartBuilder.create().uv(0, 16).cuboid(-1.5f, 0f, -1.5f, 3f, 12f, 3f), ModelTransform.pivot(2f, 12f, 0f));
        // Щупальца: растут из спины веером.
        for (int i = 0; i < TENTACLES; i++) {
            float x = (i - 2.5f) * 1.5f;
            float y = 3f + (i % 2) * 3f;
            r.addChild("tentacle" + i,
                    ModelPartBuilder.create().uv(0, 32).cuboid(-1f, 0f, -1f, 2f, 22f, 2f),
                    ModelTransform.of(x, y, 2.5f, 0.45f + 0.1f * (i % 3), 0f, (i - 2.5f) * 0.18f));
        }
        return TexturedModelData.of(data, 64, 64);
    }

    @Override
    public void setAngles(CreditorEntity e, float limbAngle, float limbDistance, float anim, float headYaw, float headPitch) {
        head.yaw = headYaw * MathHelper.RADIANS_PER_DEGREE;
        head.pitch = headPitch * MathHelper.RADIANS_PER_DEGREE;

        float swing = MathHelper.cos(limbAngle * 0.6662f) * 1.2f * limbDistance;
        rightLeg.pitch = swing;
        leftLeg.pitch = -swing;
        rightArm.pitch = -swing * 0.5f + MathHelper.sin(anim * 0.05f) * 0.05f - 0.08f;
        leftArm.pitch = swing * 0.5f - MathHelper.sin(anim * 0.05f) * 0.05f - 0.08f;
        rightArm.roll = 0.06f;
        leftArm.roll = -0.06f;

        for (int i = 0; i < TENTACLES; i++) {
            tentacles[i].pitch = 0.45f + 0.1f * (i % 3) + MathHelper.sin(anim * 0.11f + i * 1.3f) * 0.22f;
            tentacles[i].roll = (i - 2.5f) * 0.18f + MathHelper.sin(anim * 0.07f + i * 2.1f) * 0.15f;
        }
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float r, float g, float b, float a) {
        root.render(matrices, vertices, light, overlay, r, g, b, a);
    }
}
