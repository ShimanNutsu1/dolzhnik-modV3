package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.data.DebtKind;
import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.data.PlayerDebt;
import com.dolzhnik.debtmod.entity.CreditorEntity;
import com.dolzhnik.debtmod.entity.DepthsDebtorEntity;
import com.dolzhnik.debtmod.entity.FireDebtorEntity;
import com.dolzhnik.debtmod.entity.ForestDebtorEntity;
import com.dolzhnik.debtmod.entity.GhostEntity;
import com.dolzhnik.debtmod.entity.Manifestation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Сердце мода: учёт долга и все проявления по стадиям. */
public final class DebtManager {
    private DebtManager() {}

    /** Пороги стадий: первые знаки / изъятие / взыскание / расчёт. */
    public static final double[] STAGES = {250, 900, 2500, 5000};
    /** С этого долга Кредитора больше нельзя убить. */
    public static final double IMMORTAL_DEBT = 9000;
    private static final double DECAY_PER_SECOND = 0.01;

    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_SAY = new HashMap<>();
    private static final Map<UUID, Integer> NEXT_SPAWN = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();
    private record Task(int due, Runnable action) {}

    // ------------------------------------------------------------------ реплики Кредитора
    private static final String[] LOG_MSGS = {
            "Это дерево росло дольше, чем ты живёшь.",
            "Оно стояло здесь до тебя. И простоит после. Без тебя.",
            "Ещё одно в счёт.",
            "Лес запоминает каждый удар.",
            "Корни помнят."};
    private static final String[] ORE_MSGS = {
            "Это не твоё.",
            "Ты вынимаешь из меня кости.",
            "Каждый кусок — строчка в долге.",
            "Глубина не прощает.",
            "Записал."};
    private static final String[] BLOCK_MSGS = {
            "Ты оставляешь за собой дыры.",
            "Копай. Я считаю.",
            "Я слышу каждый удар кирки.",
            "Пустота за тобой тоже моя."};
    private static final String[] ANIMAL_MSGS = {
            "Оно тебе доверяло.",
            "Живое. Было.",
            "Мясо — не оправдание."};
    private static final String[] VILLAGER_MSGS = {
            "Ты заплатишь за них дороже.",
            "У них были имена."};
    private static final String[] MOB_MSGS = {
            "Убивать — тоже брать.",
            "Думаешь, это бесплатно?"};
    private static final String[] FIRE_MSGS = {
            "Огонь не торгуется.",
            "Горит лес — растёт счёт.",
            "Пепел тоже записан."};
    private static final String[] SAPLING_MSGS = {
            "Мелочь. Но я запомнил.",
            "Один росток против целого леса."};
    private static final String[] CHEST_MSGS = {
            "Ты сам их нёс. Теперь они мои.",
            "Твоё — только то, что я позволил."};
    private static final String[] SPAWN_MSGS = {
            "Пора платить.",
            "Я пришёл за своим.",
            "Ты слышал, как я считал?"};
    private static final String[] SPAWN_FOREST = {
            "Лес пришёл за своим.",
            "Не смотри. Или смотри. Корни всё равно найдут."};
    private static final String[] SPAWN_DEPTHS = {
            "Ты оставил слишком много дыр.",
            "Я иду по твоим тоннелям."};
    private static final String[] SPAWN_BLOOD = {
            "Они помнят, кто их убил.",
            "Посмотри на них. Все до одного."};
    private static final String[] SPAWN_FIRE = {
            "Пепел требует продолжения.",
            "Огонь не торгуется."};
    private static final String[] HINT2 = {
            "Долги записывают в книги. Платят золотом при свече.",
            "Принеси мне то, что взял, и я отступлю."};
    private static final String[] HINT3 = {
            "Золотой блок. Четыре книги вокруг. Свеча сверху. Принеси к ней то, что взял у меня.",
            "Не плати мусором. Я не прощаю оскорблений."};

    public static int stage(double debt) {
        int s = 0;
        for (double t : STAGES) if (debt >= t) s++;
        return s;
    }

    public static double debtOf(ServerPlayerEntity p) {
        return DebtState.get(p.getServer()).get(p.getUuid()).debt;
    }

    public static int stageOf(ServerPlayerEntity p) {
        return stage(debtOf(p));
    }

    // ------------------------------------------------------------------ чат

    public static void sayNow(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal("<Кредитор> ").formatted(Formatting.DARK_RED)
                .append(Text.literal(msg).formatted(Formatting.RED, Formatting.ITALIC)), false);
    }

    /** Реплика с вероятностью и паузой. Чем выше стадия, тем чаще. */
    public static void say(ServerPlayerEntity p, double debt, String[] pool, double baseChance) {
        if (debt < 100 || p.isCreative()) return;
        int stage = stage(debt);
        int now = p.getServer().getTicks();
        Integer last = LAST_SAY.get(p.getUuid());
        if (last != null && now - last < (stage >= 2 ? 200 : 360)) return;
        if (p.getRandom().nextDouble() > Math.min(1.0, baseChance * (1 + 0.5 * stage))) return;
        LAST_SAY.put(p.getUuid(), now);
        sayNow(p, pool[p.getRandom().nextInt(pool.length)]);
    }

    // ------------------------------------------------------------------ учёт

    public static void onBlockBroken(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState s, BlockEntity be) {
        if (p.isCreative() || w.getRegistryKey() != World.OVERWORLD) return;
        double weight = DebtWeights.forBlock(s, w, pos);
        if (weight <= 0) return;

        DebtState st = DebtState.get(w.getServer());
        PlayerDebt d = st.get(p.getUuid());
        boolean isLog = s.isIn(BlockTags.LOGS);
        // Лес: брёвна, листва, растения. Недра: всё остальное (земля, камень, руды).
        boolean isGreen = isLog || s.isIn(BlockTags.LEAVES) || s.getBlock() instanceof PlantBlock;
        d.add(isGreen ? DebtKind.FOREST : DebtKind.DEPTHS, weight);
        Ledger.countBlock(d, s);
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (weight >= 1) {
            st.chunkDebt.addTo(new ChunkPos(pos).toLong(), weight);
            if (!isLog) d.addMined(pos.asLong(), w.random);
            for (ItemStack stack : Block.getDroppedStacks(s, w, pos, be, p, p.getMainHandStack())) {
                if (!stack.isEmpty()) d.minedItems.add(Registries.ITEM.getId(stack.getItem()).toString());
            }
        }
        // Срубил дерево, и позже на его месте вырастет чёрный ствол.
        if (isLog && stage(d.debt) >= 2 && w.random.nextFloat() < 0.55f) {
            st.trunkPos.add(pos.asLong());
            st.trunkDue.add(w.getTime() + 400 + w.random.nextInt(1600));
        }
        st.markDirty();

        if (isLog) say(p, d.debt, LOG_MSGS, 0.45);
        else if (path.endsWith("_ore") || path.contains("ancient_debris")) say(p, d.debt, ORE_MSGS, 0.35);
        else if (weight >= 1) say(p, d.debt, BLOCK_MSGS, 0.03);
    }

    public static void onKill(ServerPlayerEntity p, LivingEntity victim) {
        if (p.isCreative() || victim instanceof PlayerEntity || victim instanceof Manifestation) return;
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        // Кровь: любое убийство. Запоминаем, кого убил: из них встанут призраки.
        d.add(DebtKind.BLOOD, DebtWeights.forEntity(victim));
        d.addKilled(String.format(Locale.ROOT, "%s|%.2f|%.2f",
                Registries.ENTITY_TYPE.getId(victim.getType()), victim.getWidth(), victim.getHeight()), p.getRandom());
        Ledger.countKill(d, victim);
        st.markDirty();

        String id = Registries.ENTITY_TYPE.getId(victim.getType()).getPath();
        if (id.equals("creditor")) return;
        if (id.equals("villager") || id.equals("wandering_trader")) say(p, d.debt, VILLAGER_MSGS, 0.9);
        else if (victim instanceof AnimalEntity) say(p, d.debt, ANIMAL_MSGS, 0.5);
        else say(p, d.debt, MOB_MSGS, 0.06);
    }

    public static void onFire(ServerPlayerEntity p) {
        addDebt(p, 5, DebtKind.FIRE);
        if (!p.isCreative()) {
            DebtState fs = DebtState.get(p.getServer());
            fs.get(p.getUuid()).count("fire");
            fs.markDirty();
        }
        say(p, debtOf(p), FIRE_MSGS, 0.6);
    }

    public static void onSapling(ServerPlayerEntity p) {
        if (p.isCreative()) return;
        DebtState st = DebtState.get(p.getServer());
        st.get(p.getUuid()).reduce(DebtKind.FOREST, 2.5); // саженец гасит счёт леса
        st.get(p.getUuid()).count("sapling");
        st.markDirty();
        say(p, debtOf(p), SAPLING_MSGS, 0.15);
    }

    /** Долг без привязки к счёту: доли четырёх счетов сохраняются. */
    public static void addDebt(ServerPlayerEntity p, double amount) {
        addDebt(p, amount, null);
    }

    public static void addDebt(ServerPlayerEntity p, double amount, DebtKind kind) {
        if (p.isCreative()) return;
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        if (amount < 0) d.reduce(-amount);
        else if (kind != null) d.add(kind, amount);
        else d.setTotal(d.debt + amount);
        st.markDirty();
    }

    // ------------------------------------------------------------------ тик

    public static void tick(MinecraftServer server) {
        int t = server.getTicks();
        runTasks(t);
        DebtState st = DebtState.get(server);
        if (t % 20 == 0) processTrunks(server, st);
        long gameTime = server.getOverworld().getTime();

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isCreative() || p.isSpectator()) continue;
            PlayerDebt d = st.players.get(p.getUuid());
            if (d != null) Deal.tickPenalties(p, d, st, t, gameTime); // условия сделки идут своим чередом
            if (d == null || d.debt <= 0) continue;

            if (t % 20 == 0) { d.reduce(DECAY_PER_SECOND); st.markDirty(); }
            int stage = stage(d.debt);
            Ledger.tick(p, d, st, t, gameTime);
            Deal.tickDeal(p, d, st, t, gameTime);
            if (stage < 1) continue;
            if (p.getServerWorld().getRegistryKey() != World.OVERWORLD) continue;

            // После ритуала он отступает: остаются только лёгкие знаки.
            int eff = gameTime < d.peaceUntil ? 1 : stage;

            footsteps(p, eff, t);
            if (t % 40 == 7) regrow(p, d, eff);
            if (eff >= 2) {
                if (t % 60 == 21) snuffTorches(p, d);
                if (t % 200 == 33) stealFromChests(p, d, st, eff);
                if (t % 300 == 47 && p.getRandom().nextFloat() < 0.4f) echo(p, d);
                if (t % 200 == 91) Deal.maybeOffer(p, d, st, gameTime);
                if (t % 2400 == 777 && p.getRandom().nextFloat() < 0.6f) {
                    String[] pool = eff >= 3 ? HINT3 : HINT2;
                    sayNow(p, pool[p.getRandom().nextInt(pool.length)]);
                }
            }
            if (eff >= 3 && t % 100 == 61) ravageHome(p, eff, d.debt);
            if (eff >= 3 && t % 600 == 99) maybeSpawn(p, eff, t);
        }
    }

    /** Выполнить действие через delayTicks тиков сервера. */
    public static void later(MinecraftServer server, int delayTicks, Runnable action) {
        TASKS.add(new Task(server.getTicks() + delayTicks, action));
    }

    private static void runTasks(int now) {
        for (int i = TASKS.size() - 1; i >= 0; i--) {
            Task t = TASKS.get(i);
            if (t.due() <= now) { TASKS.remove(i); t.action().run(); }
        }
    }

    // ------------------------------------------------------------------ стадия 1

    /** Шаги, которые раздаются чуть позже твоих собственных. */
    private static void footsteps(ServerPlayerEntity p, int stage, int t) {
        if (t % 10 != 0) return;
        Vec3d now = p.getPos();
        Vec3d prev = LAST_POS.put(p.getUuid(), now);
        if (prev == null || !p.isOnGround()) return;

        boolean moving = now.squaredDistanceTo(prev) > 0.8 * 0.8 && !p.isSneaking();
        float chance = moving ? 0.04f + 0.03f * stage : (stage >= 3 ? 0.015f : 0f);
        if (p.getRandom().nextFloat() > chance) return;

        int delay = moving ? 4 + p.getRandom().nextInt(3) : 0;
        TASKS.add(new Task(t + delay, () -> {
            if (p.isRemoved()) return;
            ServerWorld w = p.getServerWorld();
            Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
            if (look.lengthSquared() < 1e-4) return;
            Vec3d pos = p.getPos().subtract(look.normalize().multiply(1.6));
            BlockState below = w.getBlockState(BlockPos.ofFloored(p.getPos()).down());
            playTo(p, below.getSoundGroup().getStepSound(), pos.x, pos.y, pos.z, 0.8f, 0.9f + p.getRandom().nextFloat() * 0.2f);
        }));
    }

    /** Ночью шахты, которые ты вырыл, зарастают. Появляются блоки, которых ты не ставил. */
    private static void regrow(ServerPlayerEntity p, PlayerDebt d, int stage) {
        ServerWorld w = p.getServerWorld();
        if (!w.isNight() || d.mined.isEmpty()) return;
        float chance = 0.25f + 0.15f * stage;
        for (int k = 0; k < 6; k++) {
            if (w.random.nextFloat() > chance) continue;
            int i = w.random.nextInt(d.mined.size());
            BlockPos pos = BlockPos.fromLong(d.mined.getLong(i));
            double dist2 = Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos());
            if (dist2 < 25 || dist2 > 40 * 40) continue;
            if (!w.isChunkLoaded(pos) || !w.getBlockState(pos).isAir()) continue;
            if (looking(p, Vec3d.ofCenter(pos), 0.35)) continue;
            if (!w.getOtherEntities(null, new Box(pos)).isEmpty()) continue;

            BlockState place = pos.getY() < 0 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState();
            for (Direction dir : Direction.values()) {
                BlockPos np = pos.offset(dir);
                BlockState n = w.getBlockState(np);
                String path = Registries.BLOCK.getId(n.getBlock()).getPath();
                if (n.isSolidBlock(w, np) && !n.hasBlockEntity() && !n.isIn(BlockTags.LEAVES)
                        && !path.endsWith("_ore") && !path.contains("debris") && !path.contains("sand") && !path.contains("gravel")) {
                    place = n;
                    break;
                }
            }
            w.setBlockState(pos, place);
            d.mined.removeLong(i);
            return;
        }
    }

    // ------------------------------------------------------------------ стадия 2

    /** Сундуки теряют добытое. На стадии 3 они ломаются, на 4 ломаются вместе с содержимым. */
    private static void stealFromChests(ServerPlayerEntity p, PlayerDebt d, DebtState st, int stage) {
        if (d.minedItems.isEmpty() && stage < 3) return;
        ServerWorld w = p.getServerWorld();
        ChunkPos cp = p.getChunkPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(cp.x + dx, cp.z + dz);
                if (ch == null) continue;
                double cd = st.chunkDebt.get(ChunkPos.toLong(cp.x + dx, cp.z + dz));
                if (cd < 80) continue;
                float chance = (float) Math.min(0.3, cd / 5000.0);
                if (stage >= 3) chance = Math.max(chance, stage >= 4 ? 0.6f : 0.35f);
                for (BlockEntity be : new ArrayList<>(ch.getBlockEntities().values())) {
                    if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) continue;
                    if (w.random.nextFloat() > chance) continue;
                    Vec3d c = Vec3d.ofCenter(be.getPos());
                    if (c.squaredDistanceTo(p.getPos()) < 64 && looking(p, c, 0.4)) continue;

                    Inventory inv = (Inventory) be;
                    if (stage >= 3) {
                        if (stage >= 4) inv.clear();
                        w.breakBlock(be.getPos(), false);
                        say(p, d.debt, CHEST_MSGS, 0.4);
                        continue;
                    }
                    List<Integer> slots = new ArrayList<>();
                    for (int i = 0; i < inv.size(); i++) {
                        ItemStack s = inv.getStack(i);
                        if (!s.isEmpty() && d.minedItems.contains(Registries.ITEM.getId(s.getItem()).toString())) slots.add(i);
                    }
                    if (slots.isEmpty()) continue;
                    int slot = slots.get(w.random.nextInt(slots.size()));
                    ItemStack s = inv.getStack(slot);
                    if (s.getCount() > 1 && w.random.nextBoolean()) s.decrement(s.getCount() / 2);
                    else inv.setStack(slot, ItemStack.EMPTY);
                    inv.markDirty();
                }
            }
        }
    }

    /** Факелы в вырытых тобой тоннелях гаснут сами. */
    private static void snuffTorches(ServerPlayerEntity p, PlayerDebt d) {
        ServerWorld w = p.getServerWorld();
        Vec3d pp = p.getPos();
        for (int i = 0; i < d.mined.size(); i++) {
            BlockPos pos = BlockPos.fromLong(d.mined.getLong(i));
            if (Vec3d.ofCenter(pos).squaredDistanceTo(pp) > 32 * 32) continue;
            if (!w.isChunkLoaded(pos)) continue;
            BlockState s = w.getBlockState(pos);
            if (!(s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH) || s.isOf(Blocks.SOUL_TORCH) || s.isOf(Blocks.SOUL_WALL_TORCH))) continue;
            if (w.random.nextFloat() > 0.12f) continue;
            w.setBlockState(pos, Blocks.AIR.getDefaultState());
            w.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.4f, 2.0f);
            w.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
        }
    }

    /** Где-то вдали кто-то «копает» твои старые тоннели. */
    private static void echo(ServerPlayerEntity p, PlayerDebt d) {
        if (d.mined.isEmpty()) return;
        ServerWorld w = p.getServerWorld();
        for (int k = 0; k < 10; k++) {
            BlockPos pos = BlockPos.fromLong(d.mined.getLong(w.random.nextInt(d.mined.size())));
            double dist2 = Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos());
            if (dist2 < 64 || dist2 > 40 * 40) continue;
            playTo(p, SoundEvents.BLOCK_STONE_BREAK, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.0f, 0.6f + w.random.nextFloat() * 0.2f);
            return;
        }
    }

    /** Чёрные стволы вырастают на месте срубленных деревьев, пока никто не смотрит. */
    private static void processTrunks(MinecraftServer server, DebtState st) {
        if (st.trunkPos.isEmpty()) return;
        ServerWorld w = server.getOverworld();
        long now = w.getTime();
        for (int i = st.trunkPos.size() - 1; i >= 0; i--) {
            if (st.trunkDue.getLong(i) > now) continue;
            BlockPos pos = BlockPos.fromLong(st.trunkPos.getLong(i));
            if (!w.isChunkLoaded(pos)) continue;

            Vec3d c = Vec3d.ofCenter(pos);
            boolean watched = false;
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (c.squaredDistanceTo(p.getPos()) < 40 * 40 && looking(p, c, 0.5)) { watched = true; break; }
            }
            if (watched) { st.trunkDue.set(i, now + 60); continue; }

            growTrunk(w, pos);
            st.trunkPos.removeLong(i);
            st.trunkDue.removeLong(i);
            st.markDirty();
        }
    }

    /** Выращивает чёрный ствол. Возвращает его основание или null. Основание запоминается: оттуда выйдет лесной взыскатель. */
    public static BlockPos growTrunk(ServerWorld w, BlockPos pos) {
        BlockPos p = pos;
        if (!w.getBlockState(p).isAir()) p = p.up();
        if (!w.getBlockState(p).isAir()) return null;
        for (int i = 0; i < 10 && w.getBlockState(p.down()).isAir(); i++) p = p.down();
        BlockPos ground = p.down();
        if (!w.getBlockState(ground).isSolidBlock(w, ground)) return null;
        int h = 3 + w.random.nextInt(4);
        BlockState trunk = DebtMod.BLACK_TRUNK.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y);
        int placed = 0;
        for (int i = 0; i < h; i++) {
            if (!w.getBlockState(p.up(i)).isAir()) break;
            w.setBlockState(p.up(i), trunk);
            placed++;
        }
        if (placed == 0) return null;
        DebtState st = DebtState.get(w.getServer());
        if (st.grownTrunks.size() >= 200) st.grownTrunks.removeLong(0);
        st.grownTrunks.add(p.asLong());
        st.markDirty();
        return p;
    }

    // ------------------------------------------------------------------ стадия 3 и 4

    /** Дом = окрестности точки возрождения. Чем выше долг, тем больше блоков ломается. */
    private static void ravageHome(ServerPlayerEntity p, int stage, double debt) {
        BlockPos home = p.getSpawnPointPosition();
        if (home == null) return;
        ServerWorld w = p.getServerWorld();
        if (p.getSpawnPointDimension() != w.getRegistryKey()) return;
        if (!w.isChunkLoaded(home)) return;

        int count = stage >= 4
                ? 4 + (int) ((debt - STAGES[3]) / 500.0)
                : 1 + (int) ((debt - STAGES[2]) / 1000.0);
        count = Math.min(count, 40);
        int radius = stage >= 4 ? 18 : 10;

        for (int k = 0; k < count; k++) {
            for (int tries = 0; tries < 14; tries++) {
                BlockPos pos = home.add(w.random.nextBetween(-radius, radius),
                        w.random.nextBetween(-4, 8), w.random.nextBetween(-radius, radius));
                if (!w.isChunkLoaded(pos)) continue;
                BlockState s = w.getBlockState(pos);
                if (!isHouseBlock(s)) continue;
                if (stage < 4 && looking(p, Vec3d.ofCenter(pos), 0.3)
                        && Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos()) < 48 * 48) continue;
                w.breakBlock(pos, false);
                break;
            }
        }
    }

    /** Рукотворные блоки: то, из чего строят дома. (Блоки алтаря не трогаем.) */
    private static boolean isHouseBlock(BlockState s) {
        if (s.isAir()) return false;
        if (s.isOf(Blocks.GOLD_BLOCK) || s.isOf(Blocks.BOOKSHELF)) return false;
        String n = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (n.contains("candle")) return false;
        return n.contains("planks") || n.contains("door") || n.contains("glass") || n.contains("wool")
                || n.contains("brick") || n.contains("slab") || n.contains("stairs") || n.contains("fence")
                || n.endsWith("_bed") || n.contains("torch") || n.contains("lantern") || n.contains("table")
                || n.contains("furnace") || n.contains("smoker") || n.equals("chest") || n.equals("trapped_chest")
                || n.equals("barrel") || n.contains("ladder") || n.contains("carpet") || n.contains("sign")
                || n.contains("cobblestone") || n.contains("polished") || n.contains("terracotta")
                || n.contains("concrete") || n.contains("campfire")
                || n.contains("button") || n.contains("pressure_plate") || n.contains("shulker")
                || n.contains("frame") || n.equals("anvil");
    }

    /** Редкий шанс появления + долгая пауза между появлениями. */
    private static void maybeSpawn(ServerPlayerEntity p, int stage, int t) {
        Integer next = NEXT_SPAWN.get(p.getUuid());
        if (next != null && t < next) return;
        if (p.getRandom().nextFloat() > (stage >= 4 ? 0.35f : 0.2f)) return;
        if (spawnManifestation(p, null, false)) NEXT_SPAWN.put(p.getUuid(), t + (stage >= 4 ? 6000 : 9000));
    }

    /** Создаёт Кредитора далеко от игрока (40–60 блоков на поверхности, 22–36 под землёй). */
    public static boolean spawnCreditor(ServerPlayerEntity p, boolean enraged) {
        ServerWorld w = p.getServerWorld();
        if (anyManifestation(w, p)) return false;
        BlockPos stand = findFarStand(w, p, !enraged);
        if (stand == null) return false;
        CreditorEntity e = DebtMod.CREDITOR.create(w);
        if (e == null) return false;
        e.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, w.random.nextFloat() * 360f, 0f);
        if (enraged) e.setEnraged();
        w.spawnEntity(e);
        if (!enraged && p.getRandom().nextFloat() < 0.5f) sayNow(p, SPAWN_MSGS[p.getRandom().nextInt(SPAWN_MSGS.length)]);
        return true;
    }

    /** Ищет место для Кредитора далеко от игрока. needView: на поверхности предпочитает видимое. */
    public static BlockPos findFarStand(ServerWorld w, PlayerEntity p, boolean needView) {
        boolean surface = w.isSkyVisible(p.getBlockPos());
        double minR = surface ? 40 : 22, maxR = surface ? 60 : 36;
        for (int i = 0; i < 30; i++) {
            double ang = w.random.nextDouble() * Math.PI * 2;
            double r = minR + w.random.nextDouble() * (maxR - minR);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * r);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * r);
            if (!w.isChunkLoaded(new BlockPos(x, p.getBlockY(), z))) continue;
            int startY = surface ? w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) : p.getBlockY();

            BlockPos stand = null;
            for (int yy = startY + 3; yy >= startY - 6; yy--) {
                BlockPos b = new BlockPos(x, yy, z);
                if (w.getBlockState(b).isAir() && w.getBlockState(b.up()).isAir()
                        && w.getBlockState(b.down()).isSolidBlock(w, b.down())) { stand = b; break; }
            }
            if (stand == null) continue;
            if (needView && surface && !looking(p, Vec3d.ofBottomCenter(stand), 0.3)) continue;
            return stand;
        }
        return null;
    }

    // ------------------------------------------------------------------ четыре явления

    public static boolean anyManifestation(ServerWorld w, ServerPlayerEntity p) {
        return !w.getEntitiesByClass(Entity.class, p.getBoundingBox().expand(128), e -> e instanceof Manifestation).isEmpty();
    }

    /** Убрать все явления рядом с игроком (ритуал расплаты). */
    public static void dismissAll(ServerWorld w, ServerPlayerEntity p) {
        for (Entity e : w.getEntitiesByClass(Entity.class, p.getBoundingBox().expand(160), x -> x instanceof Manifestation)) e.discard();
    }

    /**
     * Присылает того, перед кем игрок больше всего виноват:
     * лес (корни), недра (обвал), кровь (призраки), огонь (пожар).
     * forceKind и forced нужны команде /debt summon.
     */
    public static boolean spawnManifestation(ServerPlayerEntity p, DebtKind forceKind, boolean forced) {
        ServerWorld w = p.getServerWorld();
        if (anyManifestation(w, p)) return false;
        DebtState st = DebtState.get(w.getServer());
        PlayerDebt d = st.get(p.getUuid());
        DebtKind kind = forceKind != null ? forceKind : d.dominant(w.random);
        boolean ok = switch (kind) {
            case FOREST -> spawnForest(p, st, forced);
            case DEPTHS -> spawnDepths(p, d, forced);
            case BLOOD -> spawnBlood(p, d, forced);
            case FIRE -> spawnFire(p, forced);
        };
        if (ok && p.getRandom().nextFloat() < 0.5f) {
            String[] pool = switch (kind) {
                case FOREST -> SPAWN_FOREST;
                case DEPTHS -> SPAWN_DEPTHS;
                case BLOOD -> SPAWN_BLOOD;
                case FIRE -> SPAWN_FIRE;
            };
            sayNow(p, pool[p.getRandom().nextInt(pool.length)]);
        }
        return ok;
    }

    // ---- лес

    private static boolean spawnForest(ServerPlayerEntity p, DebtState st, boolean forced) {
        ServerWorld w = p.getServerWorld();
        BlockPos base = findTrunk(w, p, st);
        if (base == null) {
            // Подходящего ствола нет: вырастает новый вдали.
            BlockPos stand = findFarStand(w, p, false);
            if (stand == null) return false;
            base = growTrunk(w, stand);
            if (base == null) return false;
        }
        // Ствол «расходуется»: существо вырастает из него.
        BlockPos q = base;
        for (int i = 0; i < 12 && w.getBlockState(q).isOf(DebtMod.BLACK_TRUNK); i++) {
            w.setBlockState(q, Blocks.AIR.getDefaultState());
            q = q.up();
        }
        int idx = st.grownTrunks.indexOf(base.asLong());
        if (idx >= 0) { st.grownTrunks.removeLong(idx); st.markDirty(); }

        ForestDebtorEntity e = DebtMod.FOREST_DEBTOR.create(w);
        if (e == null) return false;
        e.refreshPositionAndAngles(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, w.random.nextFloat() * 360f, 0f);
        if (forced) e.setForced();
        w.spawnEntity(e);
        return true;
    }

    private static BlockPos findTrunk(ServerWorld w, ServerPlayerEntity p, DebtState st) {
        for (int tries = 0; tries < 24 && !st.grownTrunks.isEmpty(); tries++) {
            int i = w.random.nextInt(st.grownTrunks.size());
            BlockPos pos = BlockPos.fromLong(st.grownTrunks.getLong(i));
            if (!w.isChunkLoaded(pos)) continue;
            if (!w.getBlockState(pos).isOf(DebtMod.BLACK_TRUNK)) { st.grownTrunks.removeLong(i); st.markDirty(); continue; }
            double d2 = Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos());
            if (d2 < 18 * 18 || d2 > 75 * 75) continue;
            if (looking(p, Vec3d.ofCenter(pos), 0.45)) continue; // на виду не выходит
            return pos;
        }
        return null;
    }

    // ---- недра

    private static boolean spawnDepths(ServerPlayerEntity p, PlayerDebt d, boolean forced) {
        ServerWorld w = p.getServerWorld();
        BlockPos wall = null;
        Direction face = null;
        for (int i = 0; i < 80 && wall == null; i++) {
            BlockPos a;
            if (!d.mined.isEmpty() && i < 50) {
                a = BlockPos.fromLong(d.mined.getLong(w.random.nextInt(d.mined.size()))); // твои собственные тоннели
            } else {
                a = p.getBlockPos().add(w.random.nextBetween(-30, 30), w.random.nextBetween(-8, 8), w.random.nextBetween(-30, 30));
            }
            double d2 = Vec3d.ofCenter(a).squaredDistanceTo(p.getPos());
            if (d2 < 10 * 10 || d2 > 34 * 34) continue;
            if (!w.isChunkLoaded(a)) continue;
            if (!w.getBlockState(a).isAir() || !w.getBlockState(a.up()).isAir()) continue;

            List<Direction> dirs = new ArrayList<>();
            for (Direction dr : Direction.Type.HORIZONTAL) dirs.add(dr);
            Collections.shuffle(dirs);
            for (Direction dr : dirs) {
                BlockPos w1 = a.offset(dr);
                if (!isDiggable(w.getBlockState(w1), w, w1) || !isDiggable(w.getBlockState(w1.up()), w, w1.up())) continue;
                if (!w.getBlockState(w1.down()).isSolidBlock(w, w1.down())) continue;
                wall = w1;
                face = dr;
                break;
            }
        }
        if (wall == null) return false;

        DepthsDebtorEntity e = DebtMod.DEPTHS_DEBTOR.create(w);
        if (e == null) return false;
        // Выходит из стены: два блока стены разлетаются, на их месте он стоит.
        w.breakBlock(wall, false);
        w.breakBlock(wall.up(), false);
        e.refreshPositionAndAngles(wall.getX() + 0.5, wall.getY(), wall.getZ() + 0.5, face.getOpposite().asRotation(), 0f);
        if (forced) e.setForced();
        w.spawnEntity(e);
        return true;
    }

    // ---- кровь

    private static boolean spawnBlood(ServerPlayerEntity p, PlayerDebt d, boolean forced) {
        ServerWorld w = p.getServerWorld();
        int stage = stage(d.debt);
        final int n = Math.max(10, Math.min(28, 8 + (int) (d.debt / 400.0)));
        final float ringR = stage >= 4 ? 10f : 9f;
        final long group = w.getTime() ^ p.getUuid().getLeastSignificantBits();
        final long formedAt = w.getTime() + 2 + n * 5 + 60; // кольцо собирается ~n*5 тиков, потом ещё 3 секунды тишины
        int t = p.getServer().getTicks();
        for (int i = 0; i < n; i++) {
            final float angle = (float) (Math.PI * 2 * i / n + w.random.nextFloat() * 0.25);
            final String entry = d.killed.isEmpty()
                    ? "minecraft:zombie|0.60|1.95"
                    : d.killed.get(w.random.nextInt(d.killed.size()));
            TASKS.add(new Task(t + 2 + i * 5, () -> placeGhost(p, entry, group, n, angle, ringR, formedAt, forced)));
        }
        return true;
    }

    private static void placeGhost(ServerPlayerEntity p, String entry, long group, int size, float angle, float ringR, long formedAt, boolean forced) {
        if (p.isRemoved() || p.isCreative() || p.isSpectator()) return;
        ServerWorld w = p.getServerWorld();
        BlockPos stand = standAt(w, p.getX() + Math.cos(angle) * ringR, p.getZ() + Math.sin(angle) * ringR, p.getBlockY());
        if (stand == null) return;

        float gw = 0.6f, gh = 1.8f;
        try {
            String[] parts = entry.split("\\|");
            gw = Float.parseFloat(parts[1]);
            gh = Float.parseFloat(parts[2]);
        } catch (RuntimeException ignored) {}

        GhostEntity g = DebtMod.GHOST.create(w);
        if (g == null) return;
        g.setup(gw, gh, group, size, angle, ringR, formedAt);
        float yaw = (float) (Math.toDegrees(Math.atan2(p.getZ() - stand.getZ(), p.getX() - stand.getX())) - 90.0);
        g.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, 0f);
        if (forced) g.setForced();
        w.spawnEntity(g);
        w.spawnParticles(ParticleTypes.SOUL, stand.getX() + 0.5, stand.getY() + 0.8, stand.getZ() + 0.5, 8, 0.25, 0.5, 0.25, 0.02);
    }

    /** Стоячее место в колонке (x, z) возле высоты py. */
    private static BlockPos standAt(ServerWorld w, double x, double z, int py) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(new BlockPos(bx, py, bz))) return null;
        for (int yy = py + 4; yy >= py - 6; yy--) {
            BlockPos b = new BlockPos(bx, yy, bz);
            if (w.getBlockState(b).isAir() && w.getBlockState(b.up()).isAir()
                    && w.getBlockState(b.down()).isSolidBlock(w, b.down())) return b;
        }
        return null;
    }

    // ---- огонь

    private static boolean spawnFire(ServerPlayerEntity p, boolean forced) {
        ServerWorld w = p.getServerWorld();
        BlockPos stand = findFarStand(w, p, false);
        if (stand == null) return false;
        FireDebtorEntity e = DebtMod.FIRE_DEBTOR.create(w);
        if (e == null) return false;
        e.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, w.random.nextFloat() * 360f, 0f);
        if (forced) e.setForced();
        w.spawnEntity(e);
        w.spawnParticles(ParticleTypes.FLAME, stand.getX() + 0.5, stand.getY() + 1.0, stand.getZ() + 0.5, 30, 0.4, 0.9, 0.4, 0.05);
        return true;
    }

    // ---- общие проверки блоков

    /** Блоки алтаря расплаты трогать нельзя никому. */
    private static boolean isAltarPart(BlockState s) {
        if (s.isOf(Blocks.GOLD_BLOCK) || s.isOf(Blocks.BOOKSHELF)) return true;
        return Registries.BLOCK.getId(s.getBlock()).getPath().contains("candle");
    }

    /** Можно ли сломать этот блок «по-подземному»: не жидкость, не контейнер, не слишком прочный, не алтарь. */
    public static boolean isDiggable(BlockState s, World w, BlockPos pos) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) return false;
        float h = s.getHardness(w, pos);
        if (h < 0 || h > 30) return false;
        return !isAltarPart(s);
    }

    /** Горючая постройка: доски, шерсть, двери, ограды, кровати, брёвна. Блоки алтаря не горят. */
    public static boolean isBurnableBuilding(BlockState s) {
        if (s.isAir() || isAltarPart(s)) return false;
        return s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.WOOL) || s.isIn(BlockTags.WOOL_CARPETS)
                || s.isIn(BlockTags.WOODEN_DOORS) || s.isIn(BlockTags.WOODEN_STAIRS) || s.isIn(BlockTags.WOODEN_SLABS)
                || s.isIn(BlockTags.WOODEN_FENCES) || s.isIn(BlockTags.WOODEN_TRAPDOORS)
                || s.isIn(BlockTags.LOGS_THAT_BURN) || s.isIn(BlockTags.BEDS)
                || s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.COMPOSTER);
    }

    /** «Расчёт»: Кредитор забирает 3 предмета (сначала добытые тобой). Долг НЕ списывается. */
    public static void collect(ServerPlayerEntity p) {
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());

        List<Integer> all = new ArrayList<>(), preferred = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            all.add(i);
            if (d.minedItems.contains(Registries.ITEM.getId(s.getItem()).toString())) preferred.add(i);
        }
        List<Integer> pool = preferred.size() >= 3 ? preferred : all;
        Collections.shuffle(pool);
        for (int k = 0; k < Math.min(3, pool.size()); k++) p.getInventory().setStack(pool.get(k), ItemStack.EMPTY);

        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 120, 0, false, false));
        playTo(p, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, p.getX(), p.getY(), p.getZ(), 1.0f, 0.6f);
        sayNow(p, "Этого мало.");
    }

    // ------------------------------------------------------------------ утилиты

    public static boolean looking(Entity viewer, Vec3d target, double cosThreshold) {
        Vec3d look = viewer.getRotationVec(1f).normalize();
        Vec3d to = target.subtract(viewer.getEyePos()).normalize();
        return look.dotProduct(to) > cosThreshold;
    }

    /** Звук только для одного игрока, из заданной точки. */
    public static void playTo(ServerPlayerEntity p, SoundEvent se, double x, double y, double z, float vol, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(se),
                SoundCategory.PLAYERS, x, y, z, vol, pitch, p.getServerWorld().getRandom().nextLong()));
    }
}
