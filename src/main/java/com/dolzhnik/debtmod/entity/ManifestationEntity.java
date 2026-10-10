package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import com.dolzhnik.debtmod.data.DebtKind;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Общая основа для явлений долга (лес, недра, кровь, огонь).
 * Умеет: искать жертву, уходить когда долг списан, ограничивать срок жизни,
 * считать «смотрит ли жертва на меня».
 * Логика потомков идёт в behave(), и вызывается ДО движения в этом тике,
 * поэтому «заморозка» взглядом срабатывает сразу, а не через шаг.
 */
public abstract class ManifestationEntity extends PathAwareEntity implements Manifestation {
    protected int lifetime;
    protected double debt;
    protected int stage;
    /** Создан командой или ритуалом: не исчезает из-за низкого долга. */
    protected boolean forced;
    protected ServerPlayerEntity lastVictim;
    private boolean scaled;

    protected ManifestationEntity(EntityType<? extends ManifestationEntity> type, World world) {
        super(type, world);
        this.setPersistent();
        this.experiencePoints = 0;
    }

    public void setForced() {
        this.forced = true;
    }

    /** Сколько тиков живёт явление. */
    protected abstract int maxLifetime();

    /** Основная логика, вызывается на сервере каждый тик, пока жив и есть жертва. */
    protected abstract void behave(ServerWorld w, ServerPlayerEntity target, int stage, double dist);

    /** Подстройка силы под долг. Вызывается раз в секунду. */
    protected void rescale(ServerPlayerEntity target, boolean first) {}

    /** Можно ли причинить вред. Лесной и призраки переопределяют. */
    protected boolean vulnerable() {
        return true;
    }

    /** Какой долг записывается убийце. 0 = не записывается. */
    protected double killPenalty() {
        return 1000;
    }

    @Override
    public void tick() {
        if (!getWorld().isClient) {
            ServerWorld w = (ServerWorld) getWorld();
            ServerPlayerEntity target = nearestVictim(w);
            if (target == null || ++lifetime > maxLifetime()) { discard(); return; }
            lastVictim = target;

            if (!scaled || age % 20 == 0) {
                debt = DebtManager.debtOf(target);
                rescale(target, !scaled);
                scaled = true;
            }
            stage = Math.max(DebtManager.stage(debt), forced ? 3 : 0);
            if (stage < 3) { discard(); return; } // долг уменьшили, уходит

            behave(w, target, stage, Math.sqrt(squaredDistanceTo(target)));
            if (isRemoved()) return;
        }
        super.tick();
    }

    /** Полностью остановиться: и навигация, и MoveControl, иначе он дойдёт до старой цели. */
    protected void freeze() {
        getNavigation().stop();
        getMoveControl().moveTo(getX(), getY(), getZ(), 0.0);
        setForwardSpeed(0f);
        setSidewaysSpeed(0f);
        Vec3d v = getVelocity();
        setVelocity(0, v.y, 0);
    }

    protected boolean immortal() {
        return debt >= DebtManager.IMMORTAL_DEBT;
    }

    protected ServerPlayerEntity nearestVictim(ServerWorld w) {
        ServerPlayerEntity best = null;
        double bd = 120 * 120;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isCreative() || p.isSpectator()) continue;
            double d = squaredDistanceTo(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Смотрит ли игрок на это существо (угол и прямая видимость). */
    public boolean isWatchedBy(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d to = getEyePos().subtract(p.getEyePos()).normalize();
        return look.dotProduct(to) > 0.55 && p.canSee(this);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(source, amount);
        if (source.isOf(DamageTypes.FALLING_BLOCK)) return false;
        if (!vulnerable() || immortal()) return false;
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!getWorld().isClient && !source.isOf(DamageTypes.GENERIC_KILL) && killPenalty() > 0) {
            ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity sp ? sp : null;
            if (killer != null) {
                DebtManager.addDebt(killer, killPenalty(), DebtKind.BLOOD);
                DebtManager.sayNow(killer, "Убил? Это тоже записано. Плюс проценты.");
            }
        }
        super.onDeath(source);
    }

    @Override public boolean canHaveStatusEffect(StatusEffectInstance effect) { return false; }
}
