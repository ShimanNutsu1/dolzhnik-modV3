package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import com.dolzhnik.debtmod.DebtMod;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * Лесной взыскатель. Вырастает из чёрного ствола.
 * Двигается ТОЛЬКО когда на него не смотрят. Не бьёт: подойдя, оплетает корнями.
 * Чем дольше ты рядом и не смотришь, тем сильнее корни (замедление до полной неподвижности).
 * Пока смотришь на него, корни ослабевают. Если долго смотреть, он уходит обратно в землю
 * и оставляет после себя новый чёрный ствол. Неуязвим.
 */
public class ForestDebtorEntity extends ManifestationEntity {
    private static final double ROOT_RANGE = 2.8;
    private static final int ROOT_MAX = 220;

    private int emerge = 40;
    private int stare;
    private int rootTicks;

    public ForestDebtorEntity(EntityType<? extends ForestDebtorEntity> type, World world) {
        super(type, world);
        this.setSilent(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 100.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.34)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override protected int maxLifetime() { return 7200; }
    @Override protected boolean vulnerable() { return false; }
    @Override protected double killPenalty() { return 0; }

    @Override
    protected void behave(ServerWorld w, ServerPlayerEntity target, int stage, double dist) {
        // Выход из ствола: стоит неподвижно, сыплется кора.
        if (emerge > 0) {
            if (emerge == 40) w.playSound(null, getBlockPos(), SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.HOSTILE, 1.2f, 0.4f);
            emerge--;
            freeze();
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DebtMod.BLACK_TRUNK.getDefaultState()),
                    getX(), getY() + 1.0, getZ(), 6, 0.4, 0.9, 0.4, 0.05);
            return;
        }

        boolean watched = isWatchedBy(target);
        getLookControl().lookAt(target, 30f, 30f);

        if (watched) {
            freeze();
            // Долгий взгляд: он врастает обратно и оставляет ствол.
            if (dist < 24 && ++stare > 240) { sink(w); return; }
        } else if (age % 4 == 0) {
            getNavigation().startMovingTo(target, stage >= 4 ? 1.5 : 1.1);
        }

        // Корни.
        if (dist < ROOT_RANGE && !watched) rootTicks = Math.min(ROOT_MAX, rootTicks + 3);
        else if (watched) rootTicks = Math.max(0, rootTicks - 4);
        else rootTicks = Math.max(0, rootTicks - 2);
        applyRoots(w, target);
    }

    private void applyRoots(ServerWorld w, ServerPlayerEntity target) {
        if (rootTicks <= 0) return;
        int amp = Math.min(6, rootTicks / 35); // 0..6
        if (amp >= 1) target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 8, amp, false, false));
        if (stage >= 4 && rootTicks >= 100) {
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 60, 1, false, false));
        }
        if (age % 4 == 0) {
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DebtMod.BLACK_TRUNK.getDefaultState()),
                    target.getX(), target.getY() + 0.1, target.getZ(), 2 + rootTicks / 30, 0.45, 0.1, 0.45, 0.02);
        }
        if (age % 24 == 0) {
            DebtManager.playTo(target, SoundEvents.BLOCK_WOOD_STEP, target.getX(), target.getY(), target.getZ(), 0.9f, 0.5f);
        }
    }

    private void sink(ServerWorld w) {
        w.playSound(null, getBlockPos(), SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.HOSTILE, 1.2f, 0.4f);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DebtMod.BLACK_TRUNK.getDefaultState()),
                getX(), getY() + 1.0, getZ(), 30, 0.5, 1.0, 0.5, 0.08);
        DebtManager.growTrunk(w, getBlockPos());
        discard();
    }
}
