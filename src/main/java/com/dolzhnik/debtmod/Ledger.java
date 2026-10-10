package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.data.DebtKind;
import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.data.PlayerDebt;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Книга счетов.
 *
 * Игра ведёт точный учёт всего, что игрок брал (деревья по породам, руда, убитые животные, огни).
 * Ночью у кровати без шума появляется книга (на пюпитре, а если места нет, то в сундуке рядом).
 * Число общего долга в ней НЕ написано, только вещи: «Дубов: 214. Руды: 63. Коров: 12. Огней: 3».
 *
 * Почерк зависит от долга: аккуратный текст, потом курсив, потом жирные и подчёркнутые буквы вразнобой,
 * потом часть букв названий превращается в «рваные» (обфускация). Цифры остаются точными всегда.
 * Последняя строка лежит на отдельной странице пюпитра и дописывается по букве, пока игрок на неё смотрит.
 */
public final class Ledger {
    private Ledger() {}

    public static final String TAG = "dolzhnik_ledger";

    private static final int FIRST_PAGE_ROWS = 9;   // на первой странице есть заголовок
    private static final int PAGE_ROWS = 11;
    private static final int MAX_ROWS = FIRST_PAGE_ROWS + 2 * PAGE_ROWS;
    private static final int TYPE_EVERY_TICKS = 3;  // одна буква в 3 тика

    // ------------------------------------------------------------------ названия (родительный падеж, мн. число)

    private static final Map<String, String> WOOD = Map.of(
            "oak", "Дубов", "spruce", "Елей", "birch", "Берёз", "jungle", "Джунглей",
            "acacia", "Акаций", "dark_oak", "Тёмных дубов", "mangrove", "Мангров", "cherry", "Вишен");

    private static final Map<String, String> MOBS = Map.ofEntries(
            Map.entry("cow", "Коров"), Map.entry("pig", "Свиней"), Map.entry("sheep", "Овец"),
            Map.entry("chicken", "Куриц"), Map.entry("rabbit", "Кроликов"), Map.entry("horse", "Лошадей"),
            Map.entry("donkey", "Ослов"), Map.entry("llama", "Лам"), Map.entry("wolf", "Волков"),
            Map.entry("cat", "Кошек"), Map.entry("fox", "Лис"), Map.entry("bee", "Пчёл"),
            Map.entry("goat", "Коз"), Map.entry("turtle", "Черепах"), Map.entry("parrot", "Попугаев"),
            Map.entry("squid", "Кальмаров"), Map.entry("bat", "Летучих мышей"),
            Map.entry("villager", "Жителей"), Map.entry("wandering_trader", "Торговцев"),
            Map.entry("iron_golem", "Големов"), Map.entry("zombie", "Зомби"), Map.entry("skeleton", "Скелетов"),
            Map.entry("creeper", "Криперов"), Map.entry("spider", "Пауков"), Map.entry("cave_spider", "Пещерных пауков"),
            Map.entry("enderman", "Эндерменов"), Map.entry("witch", "Ведьм"), Map.entry("slime", "Слизней"),
            Map.entry("drowned", "Утопленников"), Map.entry("phantom", "Фантомов"), Map.entry("pillager", "Разбойников"));

    // ------------------------------------------------------------------ учёт

    public static void countBlock(PlayerDebt d, BlockState s) {
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (s.isIn(BlockTags.LOGS)) d.count("wood:" + woodType(path));
        else if (s.isIn(BlockTags.LEAVES)) d.count("leaves");
        else if (path.endsWith("_ore") || path.contains("ancient_debris")) d.count("ore");
        else if (s.getBlock() instanceof PlantBlock) d.count("plants");
        else d.count("earth");
    }

    public static void countKill(PlayerDebt d, LivingEntity victim) {
        Identifier id = Registries.ENTITY_TYPE.getId(victim.getType());
        String p = id.getPath();
        if (p.equals("cod") || p.equals("salmon") || p.equals("tropical_fish") || p.equals("pufferfish")) {
            d.count("mob:fish");
            return;
        }
        d.count("mob:" + id);
    }

    private static String woodType(String path) {
        String p = path.startsWith("stripped_") ? path.substring("stripped_".length()) : path;
        for (String suf : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
            if (p.endsWith(suf)) return p.substring(0, p.length() - suf.length());
        }
        return p;
    }

    /** Русское название строки или null, если надо взять переводимое имя существа. */
    private static String ruLabel(String key) {
        if (key.startsWith("wood:")) return WOOD.getOrDefault(key.substring(5), "Деревьев");
        if (key.startsWith("mob:")) {
            String id = key.substring(4);
            if (id.equals("fish")) return "Рыб";
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            return MOBS.get(path);
        }
        return switch (key) {
            case "leaves" -> "Листвы";
            case "ore" -> "Руды";
            case "plants" -> "Растений";
            case "earth" -> "Земли и камня";
            case "fire" -> "Огней";
            case "sapling" -> "Возвращено";
            case "deal" -> "Договоров";
            default -> key;
        };
    }

    private static String translationKeyOf(String key) {
        Identifier id = Identifier.tryParse(key.substring(4));
        if (id == null) return "entity.minecraft.pig";
        return Registries.ENTITY_TYPE.getOrEmpty(id).map(EntityType::getTranslationKey).orElse("entity.minecraft.pig");
    }

    // ------------------------------------------------------------------ почерк

    /** 0 = аккуратно (долг на пороге первой стадии), 1 = совсем рваный. */
    static double rag(double debt) {
        double lo = DebtManager.STAGES[0];
        double hi = DebtManager.STAGES[3] * 1.5;
        return MathHelper.clamp((debt - lo) / (hi - lo), 0.0, 1.0);
    }

    private static Style plain(double rag) {
        return rag > 0.12 ? Style.EMPTY.withItalic(true) : Style.EMPTY;
    }

    private static Style letterStyle(double rag, long seed, int row, int idx, boolean canObfuscate) {
        Random r = new Random(seed ^ (row * 7919L) ^ (idx * 104729L));
        Style s = plain(rag);
        double x = r.nextDouble();
        if (x < 0.45 * rag) s = s.withBold(true);
        else if (x < 0.65 * rag) s = s.withUnderline(true);
        if (canObfuscate && rag > 0.45 && r.nextDouble() < (rag - 0.45) * 0.45) s = s.withObfuscated(true);
        return s;
    }

    /** Цифры всегда читаются: они никогда не «рвутся», только темнеют. */
    private static Style numberStyle(double rag) {
        Style s = plain(rag);
        if (rag > 0.3) s = s.withBold(true);
        if (rag > 0.6) s = s.withColor(Formatting.DARK_RED);
        return s;
    }

    private static void appendRuns(MutableText line, String text, double rag, long seed, int row, boolean protectFirst) {
        Style cur = null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean can = Character.isLetter(c) && !(protectFirst && i == 0);
            Style s = letterStyle(rag, seed, row, i, can);
            if (cur != null && !s.equals(cur)) {
                line.append(Text.literal(sb.toString()).setStyle(cur));
                sb.setLength(0);
            }
            cur = s;
            sb.append(c);
        }
        if (cur != null) line.append(Text.literal(sb.toString()).setStyle(cur));
    }

    private static MutableText row(String key, int count, double rag, long seed, int rowIdx) {
        MutableText line = Text.empty();
        String ru = ruLabel(key);
        if (ru != null) appendRuns(line, ru, rag, seed, rowIdx, true);
        else line.append(Text.translatable(translationKeyOf(key)).setStyle(letterStyle(rag, seed, rowIdx, 1, false)));
        line.append(Text.literal(": ").setStyle(plain(rag)));
        line.append(Text.literal(String.valueOf(count)).setStyle(numberStyle(rag)));
        line.append(Text.literal(".").setStyle(plain(rag)));
        return line;
    }

    private static Text joinRows(List<MutableText> rows) {
        MutableText t = Text.empty();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) t.append(Text.literal("\n"));
            t.append(rows.get(i));
        }
        return t;
    }

    /** Последняя страница: пустое место сверху и одна строка, которая дописывается по буквам. */
    private static Text lastPage(String line, int shown, double rag, long seed) {
        MutableText t = Text.literal("\n\n\n\n\n");
        MutableText typed = Text.empty();
        appendRuns(typed, line.substring(0, Math.min(shown, line.length())), rag, seed, 9999, true);
        t.append(typed);
        if (shown < line.length()) t.append(Text.literal("_").setStyle(plain(rag)));
        return t;
    }

    private static String finalLine(PlayerDebt d, int stage, Random rnd) {
        if (stage <= 1) {
            String[] pool = {"Пока я только записываю.", "Это только начало счёта."};
            return pool[rnd.nextInt(pool.length)];
        }
        if (stage == 2) {
            String[] pool = {"Это уже не мелочи.", "Ты ведь не думал, что это бесплатно?"};
            return pool[rnd.nextInt(pool.length)];
        }
        DebtKind k = d.dominant(new net.minecraft.util.math.random.CheckedRandom(rnd.nextLong()));
        String to = switch (k) {
            case FOREST -> "лесу";
            case DEPTHS -> "глубине";
            case BLOOD -> "мёртвым";
            case FIRE -> "огню";
        };
        return "Больше всего ты должен " + to + "." + (stage >= 4 ? " Я иду." : "");
    }

    // ------------------------------------------------------------------ сама книга

    /** @param typed true: последняя строка ещё не написана (для пюпитра), false: уже вся (для сундука) */
    static ItemStack build(ServerPlayerEntity p, PlayerDebt d, long now, boolean typed) {
        double rag = rag(d.debt);
        int stage = DebtManager.stage(d.debt);
        long seed = now ^ p.getUuid().getLeastSignificantBits();
        Random rnd = new Random(seed);

        List<Object2IntMap.Entry<String>> entries = new ArrayList<>(d.ledger.object2IntEntrySet());
        entries.sort(Comparator.<Object2IntMap.Entry<String>>comparingInt(Object2IntMap.Entry::getIntValue).reversed()
                .thenComparing(Object2IntMap.Entry::getKey));
        if (entries.size() > MAX_ROWS) entries = entries.subList(0, MAX_ROWS);

        List<String> pages = new ArrayList<>();
        List<MutableText> cur = new ArrayList<>();
        MutableText header = Text.empty();
        appendRuns(header, "Взято:", rag, seed, 9998, true);
        cur.add(header);
        int cap = FIRST_PAGE_ROWS;
        int rowIdx = 0;
        for (Object2IntMap.Entry<String> e : entries) {
            if (cur.size() >= cap + (pages.isEmpty() ? 1 : 0)) {
                pages.add(Text.Serializer.toJson(joinRows(cur)));
                cur = new ArrayList<>();
                cap = PAGE_ROWS;
            }
            cur.add(row(e.getKey(), e.getIntValue(), rag, seed, rowIdx++));
        }
        pages.add(Text.Serializer.toJson(joinRows(cur)));

        String line = finalLine(d, stage, rnd);
        int shown = typed ? 0 : line.length();
        pages.add(Text.Serializer.toJson(lastPage(line, shown, rag, seed)));

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = book.getOrCreateNbt();
        nbt.putString("title", "Книга счетов");
        nbt.putString("author", "Кредитор");
        nbt.putBoolean("resolved", true);
        nbt.putInt("generation", 3); // «потрёпанная»: копировать нельзя
        NbtList list = new NbtList();
        for (String pg : pages) list.add(NbtString.of(pg));
        nbt.put("pages", list);
        nbt.putBoolean(TAG, true);
        nbt.putString("ledger_owner", p.getUuidAsString());
        nbt.putString("ledger_line", line);
        nbt.putInt("ledger_shown", shown);
        nbt.putLong("ledger_seed", seed);
        nbt.putDouble("ledger_rag", rag);
        return book;
    }

    public static boolean isLedger(ItemStack s) {
        return s != null && s.isOf(Items.WRITTEN_BOOK) && s.hasNbt() && s.getNbt().getBoolean(TAG);
    }

    // ------------------------------------------------------------------ появление

    /** Для команды /debt book: положить книгу прямо сейчас. */
    public static boolean spawn(ServerPlayerEntity p, boolean force) {
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        if (d.hasBook) {
            // старую убираем: книга пишется заново с текущими числами
            removeAt(p.getServerWorld(), BlockPos.fromLong(d.bookPos));
            d.hasBook = false;
        }
        return trySpawn(p, d, st, p.getServerWorld().getTime(), force);
    }

    private static boolean trySpawn(ServerPlayerEntity p, PlayerDebt d, DebtState st, long now, boolean force) {
        ServerWorld w = p.getServerWorld();
        if (d.ledger.isEmpty()) return false;
        if (!force) {
            if (!w.isNight() || now < d.bookNext || DebtManager.stage(d.debt) < 1) return false;
            if (w.random.nextFloat() > 0.35f) return false;
        }
        BlockPos home = p.getSpawnPointPosition();
        if (home == null || p.getSpawnPointDimension() != w.getRegistryKey() || !w.isChunkLoaded(home)) return false;
        if (!w.getBlockState(home).isIn(BlockTags.BEDS)) return false;
        if (!force && Vec3d.ofCenter(home).squaredDistanceTo(p.getPos()) > 64 * 64) return false;

        BlockPos spot = findSpot(w, p, home, force);
        BlockPos chest = findChest(w, home);
        boolean useChest = chest != null && (spot == null || w.random.nextBoolean());
        if (!useChest && spot == null) return false;

        if (useChest) {
            Inventory inv = (Inventory) w.getBlockEntity(chest);
            ItemStack book = build(p, d, now, false);
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) { inv.setStack(i, book); break; }
            }
            inv.markDirty();
            d.bookPos = chest.asLong();
        } else {
            Direction face = Direction.getFacing(home.getX() - spot.getX(), 0, home.getZ() - spot.getZ());
            if (face.getAxis().isVertical()) face = Direction.NORTH;
            w.setBlockState(spot, Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, face).with(LecternBlock.HAS_BOOK, true));
            if (w.getBlockEntity(spot) instanceof LecternBlockEntity lb) {
                lb.setBook(build(p, d, now, true));
            } else {
                w.setBlockState(spot, Blocks.AIR.getDefaultState());
                return false;
            }
            d.bookPos = spot.asLong();
        }
        d.hasBook = true;
        d.bookBorn = now;
        d.bookSeen = 0;
        st.markDirty();
        return true;
    }

    private static BlockPos findSpot(ServerWorld w, ServerPlayerEntity p, BlockPos home, boolean force) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    BlockPos pos = home.add(dx, dy, dz);
                    if (!w.isChunkLoaded(pos)) continue;
                    if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) continue;
                    BlockPos below = pos.down();
                    if (!w.getBlockState(below).isSideSolidFullSquare(w, below, Direction.UP)) continue;
                    if (!w.getOtherEntities(null, new Box(pos)).isEmpty()) continue;
                    Vec3d c = Vec3d.ofCenter(pos);
                    // как и всё в этом моде: на глазах не появляется
                    if (!force && c.squaredDistanceTo(p.getPos()) < 30 * 30 && DebtManager.looking(p, c, 0.3)) continue;
                    double score = dx * dx + dy * dy + dz * dz + (w.isSkyVisible(pos.up()) ? 60 : 0);
                    if (score < bestScore) { bestScore = score; best = pos; }
                }
            }
        }
        return best;
    }

    private static BlockPos findChest(ServerWorld w, BlockPos home) {
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos bp = home.add(dx, dy, dz);
                    if (!w.isChunkLoaded(bp)) continue;
                    BlockEntity be = w.getBlockEntity(bp);
                    if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) continue;
                    Inventory inv = (Inventory) be;
                    for (int i = 0; i < inv.size(); i++) {
                        if (inv.getStack(i).isEmpty()) { found.add(bp); break; }
                    }
                }
            }
        }
        return found.isEmpty() ? null : found.get(w.random.nextInt(found.size()));
    }

    // ------------------------------------------------------------------ жизнь книги

    private static boolean existsAt(ServerWorld w, BlockPos pos) {
        BlockEntity be = w.getBlockEntity(pos);
        if (be instanceof LecternBlockEntity lb) return isLedger(lb.getBook());
        if (be instanceof Inventory inv) {
            for (int i = 0; i < inv.size(); i++) if (isLedger(inv.getStack(i))) return true;
        }
        return false;
    }

    /** Убирает книгу без дропа. Если это наш пюпитр, исчезает и он. */
    private static void removeAt(ServerWorld w, BlockPos pos) {
        if (!w.isChunkLoaded(pos)) return;
        BlockEntity be = w.getBlockEntity(pos);
        if (be instanceof LecternBlockEntity lb) {
            if (!isLedger(lb.getBook())) return;
            lb.setBook(ItemStack.EMPTY);
            w.setBlockState(pos, w.getBlockState(pos).with(LecternBlock.HAS_BOOK, false));
            w.setBlockState(pos, Blocks.AIR.getDefaultState());
            w.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.01);
            w.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.3f, 1.6f);
        } else if (be instanceof Inventory inv) {
            for (int i = 0; i < inv.size(); i++) if (isLedger(inv.getStack(i))) inv.setStack(i, ItemStack.EMPTY);
            inv.markDirty();
        }
    }

    private static void maintain(ServerPlayerEntity p, PlayerDebt d, DebtState st, long now) {
        ServerWorld w = p.getServerWorld();
        BlockPos pos = BlockPos.fromLong(d.bookPos);
        if (!w.isChunkLoaded(pos)) return;
        if (!existsAt(w, pos)) { // забрали или сломали
            d.hasBook = false;
            d.bookNext = now + 18000;
            st.markDirty();
            return;
        }
        Vec3d c = Vec3d.ofCenter(pos);
        double d2 = c.squaredDistanceTo(p.getPos());
        if (d.bookSeen == 0 && d2 < 36 && DebtManager.looking(p, c, 0.8)) {
            d.bookSeen = now;
            st.markDirty();
        }
        boolean watched = d2 < 100 && DebtManager.looking(p, c, 0.3);
        boolean vanish = now - d.bookBorn > 72000
                || (d.bookSeen != 0 && (!w.isNight() || now - d.bookSeen > 4800));
        if (vanish && !watched) {
            removeAt(w, pos);
            d.hasBook = false;
            d.bookNext = now + 12000;
            st.markDirty();
        }
    }

    // ------------------------------------------------------------------ тик

    public static void tick(ServerPlayerEntity p, PlayerDebt d, DebtState st, int t, long now) {
        if (p.getServerWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;
        tickReading(p, d, st, t, now);
        if (t % 100 == 53) {
            if (d.hasBook) maintain(p, d, st, now);
            else trySpawn(p, d, st, now, false);
        }
    }

    /** Пока игрок читает последнюю страницу на пюпитре, строка дописывается у него на глазах. */
    private static void tickReading(ServerPlayerEntity p, PlayerDebt d, DebtState st, int t, long now) {
        if (!(p.currentScreenHandler instanceof LecternScreenHandler lh)) return;
        ItemStack book = lh.getBookItem();
        if (!isLedger(book)) return;
        NbtCompound nbt = book.getNbt();

        if (d.hasBook && d.bookSeen == 0 && p.getUuidAsString().equals(nbt.getString("ledger_owner"))) {
            d.bookSeen = now;
            st.markDirty();
        }

        NbtList pages = nbt.getList("pages", NbtElement.STRING_TYPE);
        int last = pages.size() - 1;
        if (last < 1 || lh.getPage() != last) return;
        String line = nbt.getString("ledger_line");
        int shown = nbt.getInt("ledger_shown");
        if (shown >= line.length() || t % TYPE_EVERY_TICKS != 0) return;

        shown++;
        nbt.putInt("ledger_shown", shown);
        double rag = nbt.getDouble("ledger_rag");
        long seed = nbt.getLong("ledger_seed");
        pages.set(last, NbtString.of(Text.Serializer.toJson(lastPage(line, shown, rag, seed))));

        if (shown % 2 == 0) {
            DebtManager.playTo(p, SoundEvents.ITEM_BOOK_PAGE_TURN, p.getX(), p.getY(), p.getZ(), 0.25f, 1.9f);
        }
    }
}
