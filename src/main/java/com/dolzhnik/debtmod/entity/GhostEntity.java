package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Призрак убитого тобой животного или моба. Они встают кольцом вокруг игрока,
 * молча, не нападая, и кольцо медленно сжимается. Двигаются только когда на них не смотрят.
 * Любое попадание развеивает призрака. Пока их много, они не нападают;
 * когда остаётся мало (ты развеял бо́льшую часть), оставшиеся бросаются в атаку.
 */
public class GhostEntity extends ManifestationEntity {
    private static final TrackedData<Float> GHOST_WIDTH = DataTracker.registerData(GhostEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> GHOST_HEIGHT = DataTracker.registerData(GhostEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private long groupId;
    /** До этого времени кольцо ещё собирается (призраки появляются по одному), нападать рано. */
    private long formedAt;
    private int groupSize = 1;
    private float ringAngle;
    private float ringStart = 9f;
    private boolean hostile;
    private int attackCd;

    public GhostEntity(EntityType<? extends GhostEntity> type, World world) {
        super(type, world);
        this.setSilent(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 4.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.27)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(GHOST_WIDTH, 0.6f);
        this.dataTracker.startTracking(GHOST_HEIGHT, 1.8f);
    }

    public float ghostWidth() { return this.dataTracker.get(GHOST_WIDTH); }
    public float ghostHeight() { return this.dataTracker.get(GHOST_HEIGHT); }

    /** Двуногая фигура или четвероногая: определяется по пропорциям убитого. */
    public boolean isBiped() { return ghostHeight() / Math.max(0.1f, ghostWidth()) >= 2.0f; }

    public void setup(float w, float h, long group, int size, float angle, float ringR, long formedAt) {
        this.formedAt = formedAt;
        this.dataTracker.set(GHOST_WIDTH, w);
        this.dataTracker.set(GHOST_HEIGHT, h);
        this.groupId = group;
        this.groupSize = size;
        this.ringAngle = angle;
        this.ringStart = ringR;
    }

    @Override protected int maxLifetime() { return stage >= 4 ? 5400 : 3600; }
    @Override protected double killPenalty() { return 0; }

    @Override
    protected void behave(ServerWorld w, ServerPlayerEntity target, int stage, double dist) {
        if (attackCd > 0) attackCd--;
        getLookControl().lookAt(target, 30f, 30f);

        // Пока их много, они не нападают. Считаем живых раз в полсекунды.
        if (age % 10 == 0) {
            List<GhostEntity> mates = w.getEntitiesByClass(GhostEntity.class, getBoundingBox().expand(96),
                    g -> g.groupId == this.groupId && !g.isRemoved());
            double frac = stage >= 4 ? 0.5 : 0.35;
            hostile = w.getTime() >= formedAt && mates.size() <= Math.max(2, (int) Math.ceil(groupSize * frac));
        }

        if (hostile) {
            if (age % 4 == 0) getNavigation().startMovingTo(target, 1.15);
            if (dist < 1.9 && attackCd == 0) {
                attackCd = 20;
                swingHand(Hand.MAIN_HAND);
                target.damage(w.getDamageSources().mobAttack(this), (float) getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
            }
            return;
        }

        // Кольцо сжимается: с ringStart до 4 блоков примерно за 80 секунд.
        float r = Math.max(4f, ringStart - (age / 20f) * 0.065f);
        double sx = target.getX() + Math.cos(ringAngle) * r;
        double sz = target.getZ() + Math.sin(ringAngle) * r;
        double dx = sx - getX(), dz = sz - getZ();
        boolean looked = DebtManager.looking(target, getEyePos(), 0.55) && target.canSee(this);
        if (looked || dx * dx + dz * dz < 2.25) {
            freeze();
        } else if (age % 5 == 0) {
            getNavigation().startMovingTo(sx, getY(), sz, 1.0);
        }
    }

    /** Развеять с дымком. */
    private void poof(ServerWorld w) {
        w.spawnParticles(ParticleTypes.SOUL, getX(), getY() + getHeight() * 0.5, getZ(), 14, 0.3, 0.5, 0.3, 0.03);
        discard();
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.GENERIC_KILL) || source.isOf(DamageTypes.OUT_OF_WORLD)) {
            if (!getWorld().isClient) discard();
            return true;
        }
        if (source.getAttacker() == null && source.getSource() == null) return false;
        if (!getWorld().isClient) poof((ServerWorld) getWorld());
        return true;
    }

    @Override public boolean isPushable() { return false; }
    @Override public boolean isFireImmune() { return true; }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putFloat("gw", ghostWidth());
        nbt.putFloat("gh", ghostHeight());
        nbt.putLong("group", groupId);
        nbt.putLong("formed", formedAt);
        nbt.putInt("gsize", groupSize);
        nbt.putFloat("angle", ringAngle);
        nbt.putFloat("ring", ringStart);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("gw")) this.dataTracker.set(GHOST_WIDTH, nbt.getFloat("gw"));
        if (nbt.contains("gh")) this.dataTracker.set(GHOST_HEIGHT, nbt.getFloat("gh"));
        groupId = nbt.getLong("group");
        formedAt = nbt.getLong("formed");
        groupSize = Math.max(1, nbt.getInt("gsize"));
        ringAngle = nbt.getFloat("angle");
        ringStart = nbt.getFloat("ring");
    }
}
