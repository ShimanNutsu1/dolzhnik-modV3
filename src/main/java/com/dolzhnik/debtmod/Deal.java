package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.data.DebtKind;
import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.data.PlayerDebt;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Сделка с Кредитором.
 *
 * Он сам предлагает договор в чате: «Хочешь алмазы? Я покажу жилу. Это будет стоить два сердца и три тысячи долга».
 * Цена называется ДО согласия, ничего не происходит внезапно. Согласиться можно только у алтаря расплаты
 * (золотой блок, четыре книжные полки, зажжённая свеча): пригнуться и коснуться свечи пустой рукой.
 * Простой клик пустой рукой только напоминает условия.
 *
 * Цена, одна или две из трёх:
 *   - слот хотбара закрыт (в нём лежит «печать», вытащить её нельзя), на срок PENALTY_TICKS;
 *   - максимум здоровья снижен на N сердец, на тот же срок;
 *   - долг вырос сразу (на счёт «недра», так что за жадность придёт взыскатель недр). Долг не проходит сам.
 *
 * Взамен жила (блоки одной настоящей жилы поблизости) подсвечивается сквозь стены контуром и частицами.
 */
public final class Deal {
    private Deal() {}

    public static final String LOCK_TAG = "dolzhnik_lock";
    public static final String MARK_TAG = "dolzhnik_mark";
    private static final UUID HEALTH_MOD = UUID.fromString("5f0c2a1e-8d3b-4c1a-9e6f-2b7d4a9c1e30");

    /** Сколько предложение ждёт алтаря (30 минут). */
    public static final long OFFER_TTL = 36000L;
    /** На сколько закрывается слот и отнимаются сердца (одни игровые сутки, 20 минут). */
    public static final long PENALTY_TICKS = 24000L;
    /** Сколько после подписи подсвечивается жила (двое суток). */
    public static final long ACTIVE_TTL = 48000L;
    /** Пауза между предложениями (25 минут). */
    public static final long COOLDOWN = 30000L;
    private static final int VEIN_MAX = 12;
    private static final int SEARCH_RADIUS = 40;

    // ------------------------------------------------------------------ что предлагает

    private enum Ore {
        DIAMOND("diamond", "алмазы", -64, 16, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 0x55FFFF),
        GOLD("gold", "золото", -64, 32, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 0xFFD030),
        EMERALD("emerald", "изумруды", -16, 200, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 0x30E060);

        final String id, word;
        final int yMin, yMax, glow;
        final Block a, b;

        Ore(String id, String word, int yMin, int yMax, Block a, Block b, int glow) {
            this.id = id; this.word = word; this.yMin = yMin; this.yMax = yMax; this.a = a; this.b = b; this.glow = glow;
        }

        boolean is(BlockState s) { return s.isOf(a) || s.isOf(b); }

        static Ore byId(String id) {
            for (Ore o : values()) if (o.id.equals(id)) return o;
            return null;
        }
    }

    private record Price(int hearts, boolean slot, int debt) {}

    private static List<Price> pricesFor(Ore ore) {
        return switch (ore) {
            case DIAMOND -> List.of(new Price(2, false, 3000), new Price(0, true, 2000), new Price(1, true, 0),
                    new Price(3, false, 0), new Price(0, false, 5000));
            case EMERALD -> List.of(new Price(1, false, 1000), new Price(0, true, 0), new Price(0, false, 2000));
            case GOLD -> List.of(new Price(1, false, 0), new Price(0, false, 1000), new Price(0, true, 0));
        };
    }

    private static String heartsWord(int n) {
        return switch (n) {
            case 1 -> "одно сердце";
            case 2 -> "два сердца";
            default -> "три сердца";
        };
    }

    private static String debtWord(int n) {
        return switch (n) {
            case 500 -> "пятьсот";
            case 1000 -> "тысячу";
            case 2000 -> "две тысячи";
            case 3000 -> "три тысячи";
            case 4000 -> "четыре тысячи";
            case 5000 -> "пять тысяч";
            default -> String.valueOf(n);
        };
    }

    private static String describe(int hearts, boolean slot, int debt) {
        List<String> parts = new ArrayList<>();
        if (hearts > 0) parts.add(heartsWord(hearts));
        if (slot) parts.add("одну ячейку хотбара");
        if (debt > 0) parts.add(debtWord(debt) + " долга");
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " и " + parts.get(parts.size() - 1);
    }

    private static String termWord() {
        long days = Math.max(1, PENALTY_TICKS / 24000L);
        return days == 1 ? "через сутки" : "через " + days + " сут.";
    }

    // ------------------------------------------------------------------ предложение

    /** Раз в десять секунд под землёй: шанс, что он заговорит. Вызывается со стадии 2. */
    public static void maybeOffer(ServerPlayerEntity p, PlayerDebt d, DebtState st, long now) {
        if (d.offerState != 0 || now < d.nextOffer) return;
        if (d.lockSlot >= 0 || d.lossHearts > 0) return; // не предлагает, пока не сняты прошлые условия
        ServerWorld w = p.getServerWorld();
        if (p.getBlockY() > 40 || w.isSkyVisible(p.getBlockPos())) return;
        if (p.getRandom().nextFloat() > 0.25f) return;
        if (!offer(p, false)) {
            d.nextOffer = now + 3600; // жилы поблизости нет, попробует позже
            st.markDirty();
        }
    }

    /** Составляет предложение. force нужен команде /debt offer. */
    public static boolean offer(ServerPlayerEntity p, boolean force) {
        ServerWorld w = p.getServerWorld();
        if (w.getRegistryKey() != World.OVERWORLD) return false;
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        long now = w.getTime();
        if (!force && (d.offerState != 0 || d.lockSlot >= 0 || d.lossHearts > 0)) return false;
        if (force && d.offerState != 0) { clearVein(w, d); d.offerState = 0; }

        List<Ore> order = new ArrayList<>(List.of(Ore.values()));
        Collections.shuffle(order, new java.util.Random(w.random.nextLong()));
        if (w.random.nextFloat() < 0.55f) { order.remove(Ore.DIAMOND); order.add(0, Ore.DIAMOND); }

        Ore ore = null;
        List<BlockPos> vein = List.of();
        for (Ore o : order) {
            vein = findVein(w, p.getBlockPos(), o);
            if (!vein.isEmpty()) { ore = o; break; }
        }
        if (ore == null) return false;

        List<Price> prices = pricesFor(ore);
        Price pr = prices.get(w.random.nextInt(prices.size()));
        d.offerState = 1;
        d.offerOre = ore.id;
        d.offerHearts = pr.hearts();
        d.offerSlot = pr.slot();
        d.offerDebt = pr.debt();
        d.offerUntil = now + OFFER_TTL;
        d.vein.clear();
        for (BlockPos b : vein) d.vein.add(b.asLong());
        st.markDirty();

        String cost = describe(pr.hearts(), pr.slot(), pr.debt());
        String timed = (pr.hearts() > 0 || pr.slot()) ? " Взятое вернётся " + termWord() + "." : "";
        DebtManager.sayNow(p, "Хочешь " + ore.word + "? Я покажу жилу.");
        DebtManager.playTo(p, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, p.getX(), p.getY(), p.getZ(), 0.5f, 0.5f);
        MinecraftServer server = p.getServer();
        final String m2 = "Это будет стоить " + cost + "." + timed;
        final String m3 = "Согласен: приди к алтарю, пригнись и коснись свечи пустой рукой. Не согласен: просто не приходи.";
        DebtManager.later(server, 50, () -> { if (!p.isRemoved()) DebtManager.sayNow(p, m2); });
        DebtManager.later(server, 120, () -> { if (!p.isRemoved()) DebtManager.sayNow(p, m3); });
        return true;
    }

    // ------------------------------------------------------------------ алтарь

    /** Пустая рука по зажжённой свече полного алтаря. PASS, если предложения нет. */
    public static ActionResult onAltarTouch(ServerPlayerEntity p, ServerWorld w, BlockPos candle, BlockState candleState, BlockPos gold) {
        if (w.getRegistryKey() != World.OVERWORLD) return ActionResult.PASS;
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        if (d.offerState != 1) return ActionResult.PASS;
        if (!Ritual.complete(w, gold)) return ActionResult.PASS;
        Ore ore = Ore.byId(d.offerOre);
        if (ore == null) { d.offerState = 0; st.markDirty(); return ActionResult.PASS; }

        if (!p.isSneaking()) {
            p.sendMessage(Text.literal("Договор: " + ore.word + " за " + describe(d.offerHearts, d.offerSlot, d.offerDebt)
                    + ". Принять: пригнись и коснись свечи пустой рукой.").formatted(Formatting.DARK_RED), true);
            return ActionResult.SUCCESS;
        }
        accept(p, w, d, st, ore, candle, candleState);
        return ActionResult.SUCCESS;
    }

    private static void accept(ServerPlayerEntity p, ServerWorld w, PlayerDebt d, DebtState st, Ore ore, BlockPos candle, BlockState candleState) {
        long now = w.getTime();

        // Жила могла исчезнуть, пока ты строил алтарь. Тогда сделки нет и платить не нужно.
        LongArrayList alive = new LongArrayList();
        for (int i = 0; i < d.vein.size(); i++) {
            BlockPos pos = BlockPos.fromLong(d.vein.getLong(i));
            if (!w.isChunkLoaded(pos) || ore.is(w.getBlockState(pos))) alive.add(pos.asLong());
        }
        if (alive.isEmpty()) {
            d.offerState = 0;
            d.vein.clear();
            d.nextOffer = now + COOLDOWN;
            st.markDirty();
            DebtManager.sayNow(p, "Жилу уже вынули без меня. Сделки не будет.");
            return;
        }
        d.vein.clear();
        d.vein.addAll(alive);

        d.offerState = 2;
        d.offerUntil = now + ACTIVE_TTL;
        d.count("deal");

        // Цена
        if (d.offerHearts > 0) {
            d.lossHearts = d.offerHearts;
            d.lossUntil = now + PENALTY_TICKS;
            applyHealth(p, d.lossHearts);
        }
        if (d.offerSlot) {
            d.lockSlot = p.getRandom().nextInt(9);
            d.lockUntil = now + PENALTY_TICKS;
            enforceLock(p, d.lockSlot);
        }
        if (d.offerDebt > 0) DebtManager.addDebt(p, d.offerDebt, DebtKind.DEPTHS);
        st.markDirty();

        // Свеча гаснет, как после любого платежа
        w.setBlockState(candle, candleState.with(AbstractCandleBlock.LIT, false));
        w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, candle.getX() + 0.5, candle.getY() + 0.6, candle.getZ() + 0.5, 40, 0.4, 0.7, 0.4, 0.03);
        w.playSound(null, candle, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.BLOCKS, 1.5f, 0.7f);

        DebtManager.sayNow(p, "Договор подписан.");
        BlockPos first = BlockPos.fromLong(d.vein.getLong(0));
        final String hint = hintTo(p, first, ore);
        DebtManager.later(p.getServer(), 60, () -> { if (!p.isRemoved()) DebtManager.sayNow(p, hint); });
    }

    private static String hintTo(ServerPlayerEntity p, BlockPos target, Ore ore) {
        double dx = target.getX() + 0.5 - p.getX();
        double dz = target.getZ() + 0.5 - p.getZ();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        String[] names = {"север", "северо-восток", "восток", "юго-восток", "юг", "юго-запад", "запад", "северо-запад"};
        int idx = (int) Math.round(Math.atan2(dx, -dz) / (Math.PI / 4));
        idx = ((idx % 8) + 8) % 8;
        return "Жила: " + dist + " блоков на " + names[idx] + ", глубина Y=" + target.getY() + ". Ищи свечение.";
    }

    // ------------------------------------------------------------------ тик

    /** Условия сделки: работают в любом измерении и даже при нулевом долге. */
    public static void tickPenalties(ServerPlayerEntity p, PlayerDebt d, DebtState st, int t, long now) {
        if (d.lockSlot >= 0) {
            if (now >= d.lockUntil) {
                releaseLock(p);
                d.lockSlot = -1;
                st.markDirty();
                DebtManager.sayNow(p, "Ячейка свободна. Пока.");
            } else {
                enforceLock(p, d.lockSlot);
            }
        }
        if (t % 20 == 3) {
            if (d.lossHearts > 0) {
                if (now >= d.lossUntil) {
                    removeHealth(p);
                    d.lossHearts = 0;
                    st.markDirty();
                    DebtManager.sayNow(p, "Сердца можешь забрать.");
                } else {
                    applyHealth(p, d.lossHearts);
                }
            } else {
                removeHealth(p); // на случай, если модификатор остался без записи в долге
            }
        }
    }

    /** Предложение (срок) и подсвеченная жила. Только в обычном мире. */
    public static void tickDeal(ServerPlayerEntity p, PlayerDebt d, DebtState st, int t, long now) {
        ServerWorld w = p.getServerWorld();
        if (w.getRegistryKey() != World.OVERWORLD) return;

        if (d.offerState == 1 && t % 20 == 9 && now > d.offerUntil) {
            d.offerState = 0;
            d.vein.clear();
            d.nextOffer = now + COOLDOWN;
            st.markDirty();
            DebtManager.sayNow(p, "Передумал? Как знаешь.");
            return;
        }
        if (d.offerState != 2 || t % 40 != 11) return;

        Ore ore = Ore.byId(d.offerOre);
        if (ore == null || now > d.offerUntil) {
            clearVein(w, d);
            d.offerState = 0;
            d.nextOffer = now + COOLDOWN;
            st.markDirty();
            return;
        }

        Vec3d pp = p.getPos();
        boolean changed = false;
        for (int i = d.vein.size() - 1; i >= 0; i--) {
            BlockPos pos = BlockPos.fromLong(d.vein.getLong(i));
            if (!w.isChunkLoaded(pos)) continue;
            if (!ore.is(w.getBlockState(pos))) { // вынули
                removeMarkAt(w, pos);
                d.vein.removeLong(i);
                changed = true;
                continue;
            }
            double d2 = Vec3d.ofCenter(pos).squaredDistanceTo(pp);
            if (d2 < 80 * 80) ensureMark(w, ore, pos);
            if (d2 < 40 * 40) {
                w.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.6, 0.6, 0.6, 0.0);
            }
        }
        if (d.vein.isEmpty()) {
            d.offerState = 0;
            d.nextOffer = now + COOLDOWN;
            DebtManager.sayNow(p, "Взял. Записал.");
            changed = true;
        }
        if (changed) st.markDirty();
    }

    // ------------------------------------------------------------------ закрытый слот

    public static boolean isLock(ItemStack s) {
        return s != null && s.hasNbt() && s.getNbt().getBoolean(LOCK_TAG);
    }

    private static ItemStack newLock() {
        ItemStack s = new ItemStack(Items.PAPER);
        s.setCustomName(Text.literal("Ячейка закрыта договором").formatted(Formatting.DARK_RED).styled(x -> x.withItalic(false)));
        s.getOrCreateNbt().putBoolean(LOCK_TAG, true);
        NbtList lore = new NbtList();
        lore.add(NbtString.of(Text.Serializer.toJson(Text.literal("Подписано. Откроется, когда срок выйдет.").formatted(Formatting.GRAY))));
        s.getOrCreateSubNbt("display").put("Lore", lore);
        return s;
    }

    /** Каждый тик: печать только в нужном слоте, всё остальное из него выселяется. */
    private static void enforceLock(ServerPlayerEntity p, int slot) {
        if (p.isCreative() || p.isSpectator()) return;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (i != slot && isLock(inv.getStack(i))) inv.setStack(i, ItemStack.EMPTY);
        }
        if (isLock(p.currentScreenHandler.getCursorStack())) p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);

        ItemStack in = inv.getStack(slot);
        if (isLock(in)) return;
        ItemStack displaced = in.copy();
        inv.setStack(slot, newLock());
        if (!displaced.isEmpty()) {
            if (!inv.insertStack(displaced) || !displaced.isEmpty()) p.dropItem(displaced, false);
        }
    }

    private static void releaseLock(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (isLock(inv.getStack(i))) inv.setStack(i, ItemStack.EMPTY);
        if (isLock(p.currentScreenHandler.getCursorStack())) p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------ сердца

    private static void applyHealth(ServerPlayerEntity p, int hearts) {
        EntityAttributeInstance inst = p.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (inst == null) return;
        double want = -2.0 * hearts;
        EntityAttributeModifier cur = inst.getModifier(HEALTH_MOD);
        if (cur == null || cur.getValue() != want) {
            if (cur != null) inst.removeModifier(HEALTH_MOD);
            inst.addPersistentModifier(new EntityAttributeModifier(HEALTH_MOD, "dolzhnik_deal", want, EntityAttributeModifier.Operation.ADDITION));
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void removeHealth(ServerPlayerEntity p) {
        EntityAttributeInstance inst = p.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (inst != null && inst.getModifier(HEALTH_MOD) != null) inst.removeModifier(HEALTH_MOD);
    }

    // ------------------------------------------------------------------ жила

    private static boolean inRange(Ore ore, int y) { return y >= ore.yMin && y <= ore.yMax; }

    /** Ближайшая к игроку настоящая жила (в загруженных чанках) и её соседние блоки. */
    private static List<BlockPos> findVein(ServerWorld w, BlockPos origin, Ore ore) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int topY = w.getTopY() - 1;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int cx = (origin.getX() - SEARCH_RADIUS) >> 4; cx <= (origin.getX() + SEARCH_RADIUS) >> 4; cx++) {
            for (int cz = (origin.getZ() - SEARCH_RADIUS) >> 4; cz <= (origin.getZ() + SEARCH_RADIUS) >> 4; cz++) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
                if (ch == null) continue;
                for (int x = 0; x < 16; x++) {
                    int wx = (cx << 4) + x;
                    if (Math.abs(wx - origin.getX()) > SEARCH_RADIUS) continue;
                    for (int z = 0; z < 16; z++) {
                        int wz = (cz << 4) + z;
                        if (Math.abs(wz - origin.getZ()) > SEARCH_RADIUS) continue;
                        for (int y = Math.max(ore.yMin, w.getBottomY()); y <= Math.min(ore.yMax, topY); y++) {
                            m.set(wx, y, wz);
                            if (!ore.is(ch.getBlockState(m))) continue;
                            double d2 = m.getSquaredDistance(origin);
                            if (d2 < bestD) { bestD = d2; best = m.toImmutable(); }
                        }
                    }
                }
            }
        }
        if (best == null) return List.of();

        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(best);
        seen.add(best.asLong());
        while (!queue.isEmpty() && out.size() < VEIN_MAX) {
            BlockPos c = queue.poll();
            out.add(c);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = c.add(dx, dy, dz);
                if (!seen.add(n.asLong())) continue;
                if (w.isChunkLoaded(n) && ore.is(w.getBlockState(n))) queue.add(n);
            }
        }
        return out;
    }

    private static List<Entity> marksAt(ServerWorld w, BlockPos pos) {
        return w.getEntitiesByClass(Entity.class, new Box(pos).expand(0.5), e -> e.getCommandTags().contains(MARK_TAG));
    }

    private static void removeMarkAt(ServerWorld w, BlockPos pos) {
        for (Entity e : marksAt(w, pos)) e.discard();
    }

    private static void clearVein(ServerWorld w, PlayerDebt d) {
        for (int i = 0; i < d.vein.size(); i++) {
            BlockPos pos = BlockPos.fromLong(d.vein.getLong(i));
            if (w.isChunkLoaded(pos)) removeMarkAt(w, pos);
        }
        d.vein.clear();
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }

    /**
     * Светящийся «призрак блока»: блочный дисплей с контуром, который видно сквозь стены.
     * Он чуть крупнее настоящего блока, чтобы не мерцать на гранях.
     */
    private static void ensureMark(ServerWorld w, Ore ore, BlockPos pos) {
        if (!marksAt(w, pos).isEmpty()) return;
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        NbtCompound bs = new NbtCompound();
        bs.putString("Name", Registries.BLOCK.getId(w.getBlockState(pos).getBlock()).toString());
        n.put("block_state", bs);
        n.putBoolean("Glowing", true);
        n.putInt("glow_color_override", ore.glow);
        NbtList tags = new NbtList();
        tags.add(NbtString.of(MARK_TAG));
        n.put("Tags", tags);
        NbtCompound tr = new NbtCompound();
        tr.put("translation", floats(-0.005f, -0.005f, -0.005f));
        tr.put("scale", floats(1.01f, 1.01f, 1.01f));
        tr.put("left_rotation", floats(0f, 0f, 0f, 1f));
        tr.put("right_rotation", floats(0f, 0f, 0f, 1f));
        n.put("transformation", tr);
        NbtList at = new NbtList();
        at.add(NbtDouble.of(pos.getX()));
        at.add(NbtDouble.of(pos.getY()));
        at.add(NbtDouble.of(pos.getZ()));
        n.put("Pos", at);

        Optional<Entity> e = EntityType.getEntityFromNbt(n, w);
        if (e.isPresent()) {
            e.get().refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), 0f, 0f);
            w.spawnEntity(e.get());
        }
    }

    /**
     * Нужен обработчику загрузки сущностей: метка, оставшаяся от закончившейся сделки
     * (например, в чанке, который был выгружен), при загрузке исчезает.
     */
    public static boolean isMarkValid(Entity e) {
        MinecraftServer s = e.getServer();
        if (s == null) return true;
        try {
            long pos = e.getBlockPos().asLong();
            for (PlayerDebt d : DebtState.get(s).players.values()) {
                if (d.offerState == 2 && d.vein.contains(pos)) return true;
            }
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
