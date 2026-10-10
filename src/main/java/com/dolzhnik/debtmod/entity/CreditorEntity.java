package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Кредитор.
 * Пока на него смотришь, он замирает. Отвернулся, он идёт к тебе.
 * Его можно убить: чем больше долг, тем он живучее, сильнее и быстрее.
 * При долге от IMMORTAL_DEBT он неуязвим. За каждое убийство долг растёт на 1500.
 * Умеет бить щупальцем вверх, так что башня из блоков не спасает.
 */
public class CreditorEntity extends PathAwareEntity implements Manifestation {
    private static final int LIFETIME = 4800;

    private int lifetime, stare, aggro, attackCd, strikeCd;
    private boolean relocated, scaled, collected, enraged, immortal;
    private double debt;
    private ServerPlayerEntity lastVictim;

    public CreditorEntity(EntityType<? extends CreditorEntity> type, World world) {
        super(type, world);
        this.setSilent(true);
        this.setPersistent();
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createCreditorAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 60.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
    }

    /** Оскорблённый Кредитор приходит в ярости и игнорирует взгляд. */
    public void setEnraged() {
        this.enraged = true;
        this.aggro = 600;
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        ServerWorld w = (ServerWorld) getWorld();

        ServerPlayerEntity target = nearestVictim(w);
        if (target == null || ++lifetime > LIFETIME) { discard(); return; }
        lastVictim = target;

        if (!scaled || age % 20 == 0) refreshScaling(target);
        int stage = Math.max(DebtManager.stage(debt), enraged ? 3 : 0);
        if (stage < 3) { discard(); return; } // долг уменьшили, уходит

        // Не появляется вплотную: если создан рядом (например /summon), уходит далеко.
        if (!relocated) {
            relocated = true;
            if (squaredDistanceTo(target) < 30 * 30 && !enraged) {
                BlockPos far = DebtManager.findFarStand(w, target, false);
                if (far == null) { discard(); return; }
                refreshPositionAndAngles(far.getX() + 0.5, far.getY(), far.getZ() + 0.5, random.nextFloat() * 360f, 0f);
                getNavigation().stop();
                return;
            }
        }

        if (attackCd > 0) attackCd--;
        if (strikeCd > 0) strikeCd--;
        if (aggro > 0) aggro--;
        boolean free = aggro > 0; // когда его бьют, взгляд его уже не держит

        double dist = Math.sqrt(squaredDistanceTo(target));
        boolean watched = isWatchedBy(target);
        getLookControl().lookAt(target, 30f, 30f);

        if (watched && !free) {
            getNavigation().stop();
            setVelocity(0, getVelocity().y, 0);
            if (stage == 3 && !enraged) {
                if (dist < 6) { vanishWithScare(target); return; }
                if (++stare > 100) { discard(); return; }
            }
            return;
        }

        if (age % 4 == 0) getNavigation().startMovingTo(target, stage >= 4 ? 1.6 : (free ? 1.3 : 1.1));
        if (stage == 3 && !free && dist < 4) getNavigation().stop(); // стоит за спиной и ждёт

        boolean hostile = stage >= 4 || free;
        if (!hostile) return;

        // Удар щупальцем вверх: тот, кто забрался выше головы, всё равно в зоне поражения.
        double dy = target.getY() - getY();
        double hd = Math.hypot(target.getX() - getX(), target.getZ() - getZ());
        if (strikeCd == 0 && dy > 2.2 && dy < 14 && hd < 3.0) {
            tentacleStrike(w);
            strikeCd = 50;
            return;
        }

        // Обычный удар.
        if (dist < 2.3 && attackCd == 0) {
            if (stage >= 4 && !collected) { collected = true; DebtManager.collect(target); }
            attackCd = 20;
            swingHand(Hand.MAIN_HAND);
            target.damage(w.getDamageSources().mobAttack(this), (float) getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
        }
    }

    /** Чёрное щупальце вырывается из головы и бьёт вверх по столбу высотой 14 блоков. */
    private void tentacleStrike(ServerWorld w) {
        double x = getX(), z = getZ(), baseY = getY() + getHeight();
        for (double y = baseY; y < baseY + 14; y += 0.5) {
            w.spawnParticles(ParticleTypes.SQUID_INK, x, y, z, 2, 0.15, 0.1, 0.15, 0.01);
        }
        w.playSound(null, getBlockPos(), SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 1.6f, 0.7f);
        Box column = new Box(x - 2.2, baseY - 1, z - 2.2, x + 2.2, baseY + 14, z + 2.2);
        List<LivingEntity> hit = w.getEntitiesByClass(LivingEntity.class, column, e -> e != this && !(e instanceof CreditorEntity));
        float dmg = (float) (getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE) * 0.8);
        for (LivingEntity e : hit) {
            e.damage(w.getDamageSources().mobAttack(this), dmg);
            e.setVelocity(e.getVelocity().add(0, 0.9, 0));
            e.velocityModified = true;
        }
    }

    /** Сила растёт вместе с долгом. */
    private void refreshScaling(ServerPlayerEntity target) {
        debt = DebtManager.debtOf(target);
        immortal = debt >= DebtManager.IMMORTAL_DEBT;

        double hp = Math.min(400.0, 40.0 + debt / 40.0);
        EntityAttributeInstance hpAttr = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hpAttr != null) hpAttr.setBaseValue(hp);
        EntityAttributeInstance dmg = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(Math.min(16.0, 4.0 + debt / 1000.0));
        EntityAttributeInstance spd = getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(0.30 + Math.min(0.08, debt / 100000.0));
        if (!scaled) { setHealth((float) hp); scaled = true; }
    }

    private void vanishWithScare(ServerPlayerEntity p) {
        DebtManager.playTo(p, SoundEvents.ENTITY_ENDERMAN_STARE, p.getX(), p.getY(), p.getZ(), 1.0f, 0.5f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false));
        discard();
    }

    private boolean isWatchedBy(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d to = getEyePos().subtract(p.getEyePos()).normalize();
        return look.dotProduct(to) > 0.55 && p.canSee(this);
    }

    private ServerPlayerEntity nearestVictim(ServerWorld w) {
        ServerPlayerEntity best = null;
        double bd = 120 * 120;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isCreative() || p.isSpectator()) continue;
            double d = squaredDistanceTo(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(source, amount);
        if (immortal) return false; // при огромном долге его не убить
        boolean r = super.damage(source, amount);
        if (r && !getWorld().isClient) aggro = 200;
        return r;
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!getWorld().isClient && !source.isOf(DamageTypes.GENERIC_KILL)) {
            ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity sp ? sp : lastVictim;
            if (killer != null) {
                DebtManager.addDebt(killer, 1500, com.dolzhnik.debtmod.data.DebtKind.BLOOD);
                DebtManager.sayNow(killer, "Убил? Это тоже записано. Плюс проценты.");
            }
        }
        super.onDeath(source);
    }

    @Override public boolean isFireImmune() { return true; }
    @Override public boolean canHaveStatusEffect(StatusEffectInstance effect) { return false; }
}
