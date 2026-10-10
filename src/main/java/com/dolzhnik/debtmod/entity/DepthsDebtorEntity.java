package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Взыскатель недр. Выходит из стены твоего тоннеля.
 * Идёт к тебе напрямую, ломая всё на пути (без дропа), а над собой и позади
 * обрушивает потолок. Когда подходит близко, потолок вокруг тебя тоже сыплется
 * (над твоей головой не трогает: тебя завалит, но не раздавит).
 */
public class DepthsDebtorEntity extends ManifestationEntity {
    private record Pending(BlockPos pos, int due) {}

    private final List<Pending> pending = new ArrayList<>();
    private int emerge = 30;
    private int attackCd, collapseSelfCd, collapseTargetCd, rumbleCd;

    public DepthsDebtorEntity(EntityType<? extends DepthsDebtorEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 90.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.24)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.8);
    }

    @Override protected int maxLifetime() { return 6000; }

    @Override
    protected void rescale(ServerPlayerEntity target, boolean first) {
        double hp = Math.min(300.0, 60.0 + debt / 40.0);
        EntityAttributeInstance a = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (a != null) a.setBaseValue(hp);
        EntityAttributeInstance d = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (d != null) d.setBaseValue(Math.min(14.0, 5.0 + debt / 1000.0));
        if (first) setHealth((float) hp);
    }

    @Override
    protected void behave(ServerWorld w, ServerPlayerEntity target, int stage, double dist) {
        processPending(w);
        if (attackCd > 0) attackCd--;
        if (collapseSelfCd > 0) collapseSelfCd--;
        if (collapseTargetCd > 0) collapseTargetCd--;
        if (rumbleCd > 0) rumbleCd--;

        // Выход из стены.
        if (emerge > 0) {
            if (emerge == 30) w.playSound(null, getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 1.4f, 0.6f);
            emerge--;
            freeze();
            BlockState around = w.getBlockState(getBlockPos().down());
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, around.isAir() ? net.minecraft.block.Blocks.STONE.getDefaultState() : around),
                    getX(), getY() + 1.0, getZ(), 10, 0.5, 0.9, 0.5, 0.05);
            return;
        }

        getLookControl().lookAt(target, 30f, 30f);
        getMoveControl().moveTo(target.getX(), target.getY(), target.getZ(), stage >= 4 ? 1.3 : 1.0);

        if (age % 4 == 0) digAhead(w, target);

        if (rumbleCd == 0) {
            rumbleCd = 50;
            w.playSound(null, getBlockPos(), SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 1.2f, 0.5f);
        }

        // Потолок над ним и позади: так остаётся «выработка» с обвалом.
        if (collapseSelfCd == 0) {
            collapseSelfCd = stage >= 4 ? 20 : 30;
            Vec3d back = getRotationVector().multiply(1, 0, 1);
            BlockPos behind = back.lengthSquared() > 1e-4
                    ? BlockPos.ofFloored(getPos().subtract(back.normalize().multiply(2.0)))
                    : getBlockPos();
            scheduleCollapse(w, getBlockPos(), 2, 2, 6, null);
            scheduleCollapse(w, behind, 1, 2, 12, null);
        }
        // Когда близко: потолок вокруг игрока сыплется кольцом, над его головой остаётся.
        if (collapseTargetCd == 0 && dist < 12) {
            collapseTargetCd = stage >= 4 ? 70 : 110;
            scheduleCollapse(w, target.getBlockPos(), 2, 1, 22, target);
            DebtManager.playTo(target, SoundEvents.BLOCK_DEEPSLATE_BREAK, target.getX(), target.getY() + 2, target.getZ(), 1.2f, 0.4f);
        }

        // Удар.
        if (dist < 2.6 && attackCd == 0) {
            attackCd = 24;
            swingHand(Hand.MAIN_HAND);
            target.damage(w.getDamageSources().mobAttack(this), (float) getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
            Vec3d push = target.getPos().subtract(getPos()).multiply(1, 0, 1);
            if (push.lengthSquared() > 1e-4) {
                push = push.normalize().multiply(0.7);
                target.setVelocity(target.getVelocity().add(push.x, 0.3, push.z));
                target.velocityModified = true;
            }
        }
    }

    /** Ломает блоки перед собой (3 в высоту) и, если игрок выше или ниже, пробивает ступень. */
    private void digAhead(ServerWorld w, ServerPlayerEntity target) {
        Vec3d to = target.getPos().subtract(getPos());
        Vec3d h = new Vec3d(to.x, 0, to.z);
        if (h.lengthSquared() < 1e-3) h = getRotationVector().multiply(1, 0, 1);
        if (h.lengthSquared() < 1e-3) return;
        Vec3d d = h.normalize();

        for (int dy = 0; dy < 3; dy++) breakIfDiggable(w, getBlockPos().up(dy)); // своё место
        for (double f : new double[]{0.8, 1.5}) {
            BlockPos base = BlockPos.ofFloored(getX() + d.x * f, getY(), getZ() + d.z * f);
            for (int dy = 0; dy < 3; dy++) breakIfDiggable(w, base.up(dy));
            if (to.y > 1.5) breakIfDiggable(w, base.up(3));   // вверх: ступенька
            if (to.y < -1.5) breakIfDiggable(w, base.down());  // вниз: ступенька
        }
    }

    private void breakIfDiggable(ServerWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (!DebtManager.isDiggable(s, w, pos)) return;
        w.breakBlock(pos, false, this);
    }

    /**
     * Готовит обвал потолка в колонках вокруг center (радиус r). layers: сколько блоков вниз падает в колонке.
     * delay: через сколько тиков. skip: игрок, над чьей головой НЕ обрушивать.
     */
    private void scheduleCollapse(ServerWorld w, BlockPos center, int r, int layers, int delay, ServerPlayerEntity skip) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (skip != null && Math.abs(dx) <= 0 && Math.abs(dz) <= 0) continue; // колонка игрока
                if (w.random.nextFloat() > 0.75f) continue;
                BlockPos col = center.add(dx, 0, dz);
                // Ищем потолок: первый твёрдый блок выше головы.
                for (int dy = 2; dy <= 7; dy++) {
                    BlockPos c = col.up(dy);
                    BlockState s = w.getBlockState(c);
                    if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                    for (int l = 0; l < layers; l++) {
                        BlockPos q = c.up(l);
                        if (DebtManager.isDiggable(w.getBlockState(q), w, q) && w.getBlockState(q).isSolidBlock(w, q)) {
                            pending.add(new Pending(q, age + delay + l));
                            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, w.getBlockState(q)),
                                    q.getX() + 0.5, q.getY(), q.getZ() + 0.5, 6, 0.3, 0.05, 0.3, 0.02);
                        }
                    }
                    break;
                }
            }
        }
    }

    private void processPending(ServerWorld w) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.due() > age) continue;
            it.remove();
            BlockState s = w.getBlockState(p.pos());
            if (!DebtManager.isDiggable(s, w, p.pos()) || !s.isSolidBlock(w, p.pos())) continue;
            FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, p.pos(), s);
            fb.setHurtEntities(2.0f, 20);
            fb.dropItem = false;
        }
    }
}
