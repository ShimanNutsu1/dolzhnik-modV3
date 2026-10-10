package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import com.dolzhnik.debtmod.DebtMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PlantBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Огненный взыскатель. За ним остаётся выжженный след (блок «выжженная земля»,
 * сгоревшая трава, огонь под ногами). Поджигает постройки вокруг себя.
 * Сначала идёт к твоему дому (точке возрождения) и жжёт его, потом идёт за тобой.
 * Боится воды и дождя.
 */
public class FireDebtorEntity extends ManifestationEntity {
    private static final int HOME_BURN_TICKS = 700;

    private int attackCd;
    private int homeTicks;
    private int noBurnStreak;

    public FireDebtorEntity(EntityType<? extends FireDebtorEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 60.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 80.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.5);
    }

    @Override protected int maxLifetime() { return 6000; }

    @Override
    protected void rescale(ServerPlayerEntity target, boolean first) {
        double hp = Math.min(240.0, 50.0 + debt / 50.0);
        EntityAttributeInstance a = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (a != null) a.setBaseValue(hp);
        EntityAttributeInstance d = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (d != null) d.setBaseValue(Math.min(12.0, 3.0 + debt / 1200.0));
        if (first) setHealth((float) hp);
    }

    @Override
    protected void behave(ServerWorld w, ServerPlayerEntity target, int stage, double dist) {
        if (attackCd > 0) attackCd--;
        getLookControl().lookAt(target, 30f, 30f);

        // Вода и дождь его гасят.
        if (age % 10 == 0 && isTouchingWaterOrRain()) {
            damage(w.getDamageSources().drown(), 2.0f);
            w.spawnParticles(ParticleTypes.CLOUD, getX(), getY() + 1.0, getZ(), 6, 0.3, 0.6, 0.3, 0.02);
            if (isRemoved()) return;
        }

        // Куда идти: пока не выжжен дом, к дому; потом к игроку.
        BlockPos home = target.getSpawnPointPosition();
        boolean goHome = home != null
                && target.getSpawnPointDimension() == w.getRegistryKey()
                && homeTicks < HOME_BURN_TICKS
                && Vec3d.ofCenter(home).distanceTo(getPos()) < 110;
        double speed = stage >= 4 ? 1.35 : 1.1;
        if (goHome) {
            double hd = Vec3d.ofCenter(home).distanceTo(getPos());
            if (hd > 6) {
                if (age % 10 == 0) getNavigation().startMovingTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, speed);
            } else {
                getNavigation().stop();
            }
            if (hd < 14) homeTicks++;
        } else if (age % 10 == 0) {
            getNavigation().startMovingTo(target, speed);
        }

        // След.
        if (age % 2 == 0 && isOnGround()) scorch(w);

        // Поджог построек.
        if (age % 10 == 5) {
            int n = igniteBuildings(w, goHome ? 11 : 6, goHome ? 5 : 2);
            if (goHome) {
                noBurnStreak = n == 0 ? noBurnStreak + 1 : 0;
                if (noBurnStreak > 8) homeTicks = HOME_BURN_TICKS; // гореть нечему, идёт за игроком
            }
            if (n > 0) w.playSound(null, getBlockPos(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.HOSTILE, 0.8f, 0.7f);
        }

        // Ближний бой: жжёт.
        if (!goHome || dist < 3) {
            if (dist < 2.3 && attackCd == 0) {
                attackCd = 20;
                swingHand(Hand.MAIN_HAND);
                target.damage(w.getDamageSources().mobAttack(this), (float) getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
                target.setOnFireFor(4);
            }
        }

        if (age % 3 == 0) {
            w.spawnParticles(ParticleTypes.FLAME, getX(), getY() + 1.0, getZ(), 3, 0.25, 0.8, 0.25, 0.01);
            if (age % 9 == 0) w.spawnParticles(ParticleTypes.SMOKE, getX(), getY() + 2.1, getZ(), 2, 0.2, 0.1, 0.2, 0.01);
        }
    }

    /** Выжигает землю под собой (3x3), траву и снег над ней, иногда оставляет огонь. */
    private void scorch(ServerWorld w) {
        BlockPos below = getBlockPos().down();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos g = below.add(dx, 0, dz);
                BlockState s = w.getBlockState(g);
                if (isTopsoil(s)) {
                    w.setBlockState(g, DebtMod.SCORCHED_EARTH.getDefaultState());
                    BlockPos up = g.up();
                    BlockState us = w.getBlockState(up);
                    if (us.getBlock() instanceof PlantBlock || us.isOf(Blocks.SNOW)) {
                        w.setBlockState(up, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
        BlockPos feet = getBlockPos();
        if (w.random.nextFloat() < 0.15f && w.getBlockState(feet).isAir() && Blocks.FIRE.getDefaultState().canPlaceAt(w, feet)) {
            w.setBlockState(feet, Blocks.FIRE.getDefaultState());
        }
    }

    private static boolean isTopsoil(BlockState s) {
        return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.PODZOL) || s.isOf(Blocks.MYCELIUM)
                || s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.FARMLAND) || s.isOf(Blocks.ROOTED_DIRT)
                || s.isOf(Blocks.COARSE_DIRT) || s.isOf(Blocks.MOSS_BLOCK);
    }

    /** Ставит огонь рядом с горючими блоками построек. Возвращает, сколько подожгла. */
    private int igniteBuildings(ServerWorld w, int radius, int maxCount) {
        int count = 0;
        for (int i = 0; i < 40 && count < maxCount; i++) {
            BlockPos pos = getBlockPos().add(w.random.nextBetween(-radius, radius),
                    w.random.nextBetween(-2, 5), w.random.nextBetween(-radius, radius));
            if (!w.isChunkLoaded(pos)) continue;
            if (!DebtManager.isBurnableBuilding(w.getBlockState(pos))) continue;
            for (Direction dir : Direction.values()) {
                BlockPos adj = pos.offset(dir);
                if (w.getBlockState(adj).isAir() && Blocks.FIRE.getDefaultState().canPlaceAt(w, adj)) {
                    w.setBlockState(adj, Blocks.FIRE.getDefaultState());
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    @Override public boolean isFireImmune() { return true; }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_FIRE)) return false;
        return super.damage(source, amount);
    }
}
