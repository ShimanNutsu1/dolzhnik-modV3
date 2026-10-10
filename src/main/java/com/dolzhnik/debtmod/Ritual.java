package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.data.PlayerDebt;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Алтарь расплаты.
 *
 * Конструкция: золотой блок, по четырём сторонам от него книжные полки, сверху зажжённая свеча.
 * ПКМ по свече предметом из добытого:
 *   уголь 5, медный слиток 8, железо 20, золото 50, изумруд 120, алмаз 250, незерит 800,
 * и долг падает на эту сумму, Кредитор отступает на время, свеча гаснет.
 * Мусор (гнилая плоть, паучий глаз, кость, ядовитый картофель) оскорбляет его:
 * долг +400, алтарь разрушен, Кредитор приходит немедленно и в ярости.
 */
public final class Ritual {
    private Ritual() {}

    public static ActionResult tryUse(ServerPlayerEntity p, ServerWorld w, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        BlockState s = w.getBlockState(pos);
        if (!(s.getBlock() instanceof CandleBlock) || !s.get(AbstractCandleBlock.LIT)) return ActionResult.PASS;
        BlockPos gold = pos.down();
        if (!w.getBlockState(gold).isOf(Blocks.GOLD_BLOCK)) return ActionResult.PASS;

        ItemStack stack = p.getMainHandStack();
        // Пустая рука: если Кредитор что-то предложил, это разговор о договоре (см. Deal).
        if (stack.isEmpty()) {
            ActionResult dr = Deal.onAltarTouch(p, w, pos, s, gold);
            if (dr != ActionResult.PASS) return dr;
        }
        Item item = stack.getItem();
        boolean trash = item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE
                || item == Items.BONE || item == Items.POISONOUS_POTATO;
        int value = value(item);
        if (value == 0 && !trash) return ActionResult.PASS;

        if (!complete(w, gold)) {
            p.sendMessage(Text.literal("Алтарь неполон.").formatted(Formatting.DARK_GRAY), true);
            return ActionResult.SUCCESS;
        }

        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.6, cz = pos.getZ() + 0.5;
        if (!p.isCreative()) stack.decrement(1);

        if (trash) {
            // Оскорбление.
            DebtManager.addDebt(p, 400);
            w.breakBlock(pos, false);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, cx, cy, cz, 30, 0.4, 0.5, 0.4, 0.03);
            w.playSound(null, pos, SoundEvents.ENTITY_WARDEN_ANGRY, SoundCategory.HOSTILE, 1.5f, 0.6f);
            DebtManager.sayNow(p, "Ты смеешь платить мусором?!");
            DebtManager.dismissAll(w, p); // приходит сам Кредитор, а не явление счёта
            DebtManager.spawnCreditor(p, true);
            return ActionResult.SUCCESS;
        }

        // Платёж принят.
        d.reduce(value); // списывается со всех четырёх счетов пропорционально
        long now = w.getTime();
        d.peaceUntil = now + Math.min(48000L, 6000L + value * 40L);
        st.markDirty();

        DebtManager.dismissAll(w, p); // уходят все явления: лес, недра, кровь, огонь и Кредитор

        w.setBlockState(pos, s.with(AbstractCandleBlock.LIT, false));
        w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, cx, cy, cz, 40, 0.4, 0.7, 0.4, 0.03);
        w.playSound(null, pos, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.BLOCKS, 1.5f, 0.5f);
        DebtManager.sayNow(p, "Платёж принят. Пока.");
        return ActionResult.SUCCESS;
    }

    /** Золотой блок окружён четырьмя книжными полками (север, юг, запад, восток). */
    static boolean complete(ServerWorld w, BlockPos gold) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (!w.getBlockState(gold.offset(dir)).isOf(Blocks.BOOKSHELF)) return false;
        }
        return true;
    }

    private static int value(Item i) {
        if (i == Items.COAL) return 5;
        if (i == Items.COPPER_INGOT) return 8;
        if (i == Items.IRON_INGOT) return 20;
        if (i == Items.GOLD_INGOT) return 50;
        if (i == Items.EMERALD) return 120;
        if (i == Items.DIAMOND) return 250;
        if (i == Items.NETHERITE_INGOT) return 800;
        return 0;
    }
}
