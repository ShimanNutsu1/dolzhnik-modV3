package com.dolzhnik.debtmod.data;

/**
 * Четыре счёта долга. Какой из них больше, тот и присылает взыскателя.
 * bias: во сколько раз счёт «весит» при сравнении. Огонь копится медленно
 * (поджог редкое действие), поэтому ему дана надбавка, иначе он почти никогда не победит.
 */
public enum DebtKind {
    FOREST("forest", 1.0),
    DEPTHS("depths", 1.0),
    BLOOD("blood", 1.0),
    FIRE("fire", 3.0);

    public final String id;
    public final double bias;

    DebtKind(String id, double bias) {
        this.id = id;
        this.bias = bias;
    }

    public static DebtKind byId(String s) {
        for (DebtKind k : values()) if (k.id.equalsIgnoreCase(s)) return k;
        return null;
    }
}
