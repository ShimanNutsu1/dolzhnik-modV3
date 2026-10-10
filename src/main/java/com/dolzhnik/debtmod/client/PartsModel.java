package com.dolzhnik.debtmod.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Простая двуногая модель с головой, двумя руками, двумя ногами и произвольным числом
 * «волнистых» частей (корни, языки пламени), которые называются w0, w1, ...
 */
public class PartsModel<T extends LivingEntity> extends EntityModel<T> {
    private final ModelPart root, head, rightArm, leftArm, rightLeg, leftLeg;
    private final List<ModelPart> waves = new ArrayList<>();
    private final List<float[]> waveBase = new ArrayList<>();
    private final float armPitch;
    private final float waveSpeed;

    public PartsModel(ModelPart root, int waveCount, float armPitch, float waveSpeed) {
        this.root = root;
        this.head = root.getChild("head");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.armPitch = armPitch;
        this.waveSpeed = waveSpeed;
        for (int i = 0; i < waveCount; i++) {
            ModelPart p = root.getChild("w" + i);
            waves.add(p);
            waveBase.add(new float[]{p.pitch, p.roll});
        }
    }

    @Override
    public void setAngles(T e, float limbAngle, float limbDistance, float anim, float headYaw, float headPitch) {
        head.yaw = headYaw * MathHelper.RADIANS_PER_DEGREE;
        head.pitch = headPitch * MathHelper.RADIANS_PER_DEGREE;

        float swing = MathHelper.cos(limbAngle * 0.6662f) * 1.2f * limbDistance;
        rightLeg.pitch = swing;
        leftLeg.pitch = -swing;
        rightArm.pitch = -swing * 0.5f + armPitch + MathHelper.sin(anim * 0.05f) * 0.04f;
        leftArm.pitch = swing * 0.5f + armPitch - MathHelper.sin(anim * 0.05f) * 0.04f;

        for (int i = 0; i < waves.size(); i++) {
            float[] b = waveBase.get(i);
            waves.get(i).pitch = b[0] + MathHelper.sin(anim * waveSpeed + i * 1.3f) * 0.22f;
            waves.get(i).roll = b[1] + MathHelper.sin(anim * waveSpeed * 0.65f + i * 2.1f) * 0.15f;
        }
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float r, float g, float b, float a) {
        root.render(matrices, vertices, light, overlay, r, g, b, a);
    }
}
