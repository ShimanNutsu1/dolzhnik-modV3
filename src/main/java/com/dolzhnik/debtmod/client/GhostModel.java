package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.GhostEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;

/** Призрак убитого: двуногая или четвероногая фигура в зависимости от пропорций жертвы. */
public class GhostModel extends EntityModel<GhostEntity> {
    private final ModelPart root, head, body, rightArm, leftArm, rightLeg, leftLeg;
    private final ModelPart qHead, qBody;
    private final ModelPart[] qLegs = new ModelPart[4];

    public GhostModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.qHead = root.getChild("q_head");
        this.qBody = root.getChild("q_body");
        for (int i = 0; i < 4; i++) qLegs[i] = root.getChild("q_leg" + i);
    }

    @Override
    public void setAngles(GhostEntity e, float limbAngle, float limbDistance, float anim, float headYaw, float headPitch) {
        boolean biped = e.isBiped();
        head.visible = body.visible = rightArm.visible = leftArm.visible = rightLeg.visible = leftLeg.visible = biped;
        qHead.visible = qBody.visible = !biped;
        for (ModelPart p : qLegs) p.visible = !biped;

        float swing = MathHelper.cos(limbAngle * 0.6662f) * 1.2f * limbDistance;
        float yaw = headYaw * MathHelper.RADIANS_PER_DEGREE;
        float pitch = headPitch * MathHelper.RADIANS_PER_DEGREE;
        if (biped) {
            head.yaw = yaw;
            head.pitch = pitch;
            rightLeg.pitch = swing;
            leftLeg.pitch = -swing;
            // Руки безвольно висят и чуть покачиваются.
            rightArm.pitch = -swing * 0.5f + MathHelper.sin(anim * 0.05f) * 0.05f;
            leftArm.pitch = swing * 0.5f - MathHelper.sin(anim * 0.05f) * 0.05f;
        } else {
            qHead.yaw = yaw;
            qHead.pitch = pitch;
            qLegs[0].pitch = swing;
            qLegs[1].pitch = -swing;
            qLegs[2].pitch = -swing;
            qLegs[3].pitch = swing;
        }
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float r, float g, float b, float a) {
        root.render(matrices, vertices, light, overlay, r, g, b, a);
    }
}
