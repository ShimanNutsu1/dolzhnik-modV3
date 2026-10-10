package com.dolzhnik.debtmod.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Скрытый долговой счёт одного игрока. */
public class PlayerDebt {
    public static final int MAX_MINED = 8000;
    public static final int MAX_KILLED = 48;

    /** Общий долг. Он решает, какая стадия. */
    public double debt;
    /** Разбивка общего долга по четырём счетам (лес, недра, кровь, огонь). */
    public final double[] accounts = new double[DebtKind.values().length];
    /** До какого игрового времени Кредитор «отступил» после ритуала. */
    public long peaceUntil;
    /** Позиции блоков, которые игрок вырыл (для «зарастания» шахт и гаснущих факелов). */
    public final LongArrayList mined = new LongArrayList();
    /** Предметы, которые игрок добыл. Именно их мир заберёт из сундуков. */
    public final Set<String> minedItems = new HashSet<>();
    /** Последние убитые игроком существа в виде «id|ширина|высота». Из них встают призраки. */
    public final List<String> killed = new ArrayList<>();

    // ------------------------------------------------------------------ книга счетов

    /** Точный учёт всего, что игрок брал: ключ вида «wood:oak», «ore», «mob:minecraft:cow», «fire». */
    public final Object2IntOpenHashMap<String> ledger = new Object2IntOpenHashMap<>();
    /** Книга сейчас лежит где-то у кровати. */
    public boolean hasBook;
    public long bookPos, bookBorn, bookSeen, bookNext;

    public void count(String key) {
        ledger.addTo(key, 1);
    }

    // ------------------------------------------------------------------ сделка с Кредитором

    /** 0 нет, 1 предложена (ждёт алтаря), 2 принята (жила подсвечена). */
    public int offerState;
    public String offerOre = "";
    public int offerHearts, offerDebt;
    public boolean offerSlot;
    public long offerUntil, nextOffer;
    /** Блоки показанной жилы. */
    public final LongArrayList vein = new LongArrayList();
    /** Заблокированный слот хотбара (-1 нет) и время, когда он освободится. */
    public int lockSlot = -1;
    public long lockUntil;
    /** Сколько сердец отнято и до какого времени. */
    public int lossHearts;
    public long lossUntil;

    public void addMined(long pos, Random rnd) {
        if (mined.size() < MAX_MINED) mined.add(pos);
        else mined.set(rnd.nextInt(mined.size()), pos);
    }

    public void addKilled(String entry, Random rnd) {
        if (killed.size() < MAX_KILLED) killed.add(entry);
        else killed.set(rnd.nextInt(killed.size()), entry);
    }

    // ------------------------------------------------------------------ счета

    /** Записать долг на конкретный счёт (и в общий). */
    public void add(DebtKind kind, double amount) {
        if (amount <= 0) return;
        debt += amount;
        accounts[kind.ordinal()] += amount;
    }

    /** Списать долг со всех счетов пропорционально (распад, ритуал). */
    public void reduce(double amount) {
        if (amount <= 0) return;
        setTotal(debt - amount);
    }

    /** Списать долг с одного счёта (саженец гасит «лес»). */
    public void reduce(DebtKind kind, double amount) {
        if (amount <= 0) return;
        double take = Math.min(amount, accounts[kind.ordinal()]);
        accounts[kind.ordinal()] -= take;
        debt = Math.max(0, debt - take);
        // Если на этом счёте не хватило, остаток списываем со всех пропорционально.
        if (amount - take > 0) setTotal(debt - (amount - take));
    }

    /** Задать общий долг, масштабируя счета так, чтобы их доли сохранились. */
    public void setTotal(double value) {
        value = Math.max(0, value);
        double sum = 0;
        for (double a : accounts) sum += a;
        if (sum > 0) {
            double f = value / sum;
            for (int i = 0; i < accounts.length; i++) accounts[i] *= f;
        }
        debt = value;
    }

    public double account(DebtKind kind) {
        return accounts[kind.ordinal()];
    }

    /** Самый большой счёт с учётом надбавок. Если счета пусты (старое сохранение или команда), случайный. */
    public DebtKind dominant(Random rnd) {
        DebtKind best = null;
        double bestVal = 0;
        for (DebtKind k : DebtKind.values()) {
            double v = accounts[k.ordinal()] * k.bias;
            if (v > bestVal) { bestVal = v; best = k; }
        }
        if (best == null) {
            DebtKind[] all = DebtKind.values();
            best = all[rnd.nextInt(all.length)];
        }
        return best;
    }

    // ------------------------------------------------------------------ сохранение

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putDouble("debt", debt);
        n.putLong("peace", peaceUntil);
        n.putLongArray("mined", mined.toLongArray());
        NbtList items = new NbtList();
        for (String s : minedItems) items.add(NbtString.of(s));
        n.put("items", items);
        for (DebtKind k : DebtKind.values()) n.putDouble("acc_" + k.id, accounts[k.ordinal()]);
        NbtList kl = new NbtList();
        for (String s : killed) kl.add(NbtString.of(s));
        n.put("killed", kl);

        NbtCompound lg = new NbtCompound();
        for (Object2IntMap.Entry<String> e : ledger.object2IntEntrySet()) lg.putInt(e.getKey(), e.getIntValue());
        n.put("ledger", lg);
        n.putBoolean("hasBook", hasBook);
        n.putLong("bookPos", bookPos);
        n.putLong("bookBorn", bookBorn);
        n.putLong("bookSeen", bookSeen);
        n.putLong("bookNext", bookNext);

        n.putInt("offerState", offerState);
        n.putString("offerOre", offerOre);
        n.putInt("offerHearts", offerHearts);
        n.putInt("offerDebt", offerDebt);
        n.putBoolean("offerSlot", offerSlot);
        n.putLong("offerUntil", offerUntil);
        n.putLong("nextOffer", nextOffer);
        n.putLongArray("vein", vein.toLongArray());
        n.putInt("lockSlot", lockSlot);
        n.putLong("lockUntil", lockUntil);
        n.putInt("lossHearts", lossHearts);
        n.putLong("lossUntil", lossUntil);
        return n;
    }

    public static PlayerDebt fromNbt(NbtCompound n) {
        PlayerDebt d = new PlayerDebt();
        d.debt = n.getDouble("debt");
        d.peaceUntil = n.getLong("peace");
        for (long l : n.getLongArray("mined")) d.mined.add(l);
        NbtList items = n.getList("items", NbtElement.STRING_TYPE);
        for (int i = 0; i < items.size(); i++) d.minedItems.add(items.getString(i));
        for (DebtKind k : DebtKind.values()) d.accounts[k.ordinal()] = n.getDouble("acc_" + k.id);
        NbtList kl = n.getList("killed", NbtElement.STRING_TYPE);
        for (int i = 0; i < kl.size(); i++) d.killed.add(kl.getString(i));

        NbtCompound lg = n.getCompound("ledger");
        for (String k : lg.getKeys()) d.ledger.put(k, lg.getInt(k));
        d.hasBook = n.getBoolean("hasBook");
        d.bookPos = n.getLong("bookPos");
        d.bookBorn = n.getLong("bookBorn");
        d.bookSeen = n.getLong("bookSeen");
        d.bookNext = n.getLong("bookNext");

        d.offerState = n.getInt("offerState");
        d.offerOre = n.getString("offerOre");
        d.offerHearts = n.getInt("offerHearts");
        d.offerDebt = n.getInt("offerDebt");
        d.offerSlot = n.getBoolean("offerSlot");
        d.offerUntil = n.getLong("offerUntil");
        d.nextOffer = n.getLong("nextOffer");
        for (long l : n.getLongArray("vein")) d.vein.add(l);
        d.lockSlot = n.contains("lockSlot") ? n.getInt("lockSlot") : -1;
        d.lockUntil = n.getLong("lockUntil");
        d.lossHearts = n.getInt("lossHearts");
        d.lossUntil = n.getLong("lossUntil");
        return d;
    }
}
