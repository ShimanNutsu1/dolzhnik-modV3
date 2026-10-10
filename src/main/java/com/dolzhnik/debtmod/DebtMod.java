package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.block.BlackTrunkBlock;
import com.dolzhnik.debtmod.block.ScorchedEarthBlock;
import com.dolzhnik.debtmod.data.DebtKind;
import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.entity.CreditorEntity;
import com.dolzhnik.debtmod.entity.DepthsDebtorEntity;
import com.dolzhnik.debtmod.entity.FireDebtorEntity;
import com.dolzhnik.debtmod.entity.ForestDebtorEntity;
import com.dolzhnik.debtmod.entity.GhostEntity;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.SaplingBlock;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

public class DebtMod implements ModInitializer {
    public static final String MOD_ID = "dolzhnik";

    public static final Block BLACK_TRUNK = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "black_trunk"), new BlackTrunkBlock());
    public static final Item BLACK_TRUNK_ITEM = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "black_trunk"),
            new BlockItem(BLACK_TRUNK, new FabricItemSettings()));

    public static final EntityType<CreditorEntity> CREDITOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "creditor"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, CreditorEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 2.1f)).build());

    /** Выжженная земля. Предмета у неё нет: получить можно только от огненного взыскателя. */
    public static final Block SCORCHED_EARTH = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "scorched_earth"), new ScorchedEarthBlock());

    public static final EntityType<ForestDebtorEntity> FOREST_DEBTOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "forest_debtor"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, ForestDebtorEntity::new)
                    .dimensions(EntityDimensions.fixed(0.8f, 2.7f)).build());
    public static final EntityType<DepthsDebtorEntity> DEPTHS_DEBTOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "depths_debtor"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, DepthsDebtorEntity::new)
                    .dimensions(EntityDimensions.fixed(0.9f, 2.4f)).build());
    public static final EntityType<FireDebtorEntity> FIRE_DEBTOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "fire_debtor"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, FireDebtorEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 2.0f)).build());
    public static final EntityType<GhostEntity> GHOST = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "debt_ghost"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, GhostEntity::new)
                    .dimensions(EntityDimensions.fixed(0.7f, 1.8f)).build());

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(CREDITOR, CreditorEntity.createCreditorAttributes());
        FabricDefaultAttributeRegistry.register(FOREST_DEBTOR, ForestDebtorEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(DEPTHS_DEBTOR, DepthsDebtorEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(FIRE_DEBTOR, FireDebtorEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(GHOST, GhostEntity.createAttributes());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(e -> e.add(BLACK_TRUNK_ITEM));

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return;
            DebtManager.onBlockBroken(sp, (ServerWorld) world, pos, state, be);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity sp) DebtManager.onKill(sp, entity);
        });

        // Печати сделки, выброшенные из закрытого слота, исчезают. Забытые метки жилы тоже.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ItemEntity ie && Deal.isLock(ie.getStack())) ie.discard();
            else if (entity.getCommandTags().contains(Deal.MARK_TAG) && !Deal.isMarkValid(entity)) entity.discard();
        });

        // Ритуал у алтаря. Огонь добавляет долг, посадка саженцев немного его гасит.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ActionResult r = Ritual.tryUse(sp, (ServerWorld) world, hand, hit);
            if (r != ActionResult.PASS) return r;
            ItemStack st = player.getStackInHand(hand);
            if (st.getItem() instanceof BlockItem bi && bi.getBlock() instanceof SaplingBlock) {
                DebtManager.onSapling(sp);
            } else if (st.isOf(Items.FLINT_AND_STEEL) || st.isOf(Items.FIRE_CHARGE) || st.isOf(Items.LAVA_BUCKET)) {
                DebtManager.onFire(sp);
            }
            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(DebtManager::tick);

        // Команды для операторов: /debt get|set|add <игрок> [значение]. Нужны для тестов.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("debt")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("get")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                                    ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                    double v = DebtState.get(ctx.getSource().getServer()).get(t.getUuid()).debt;
                                    ctx.getSource().sendFeedback(() -> Text.literal(t.getName().getString()
                                            + ": долг " + (int) v + ", стадия " + DebtManager.stage(v)), false);
                                    return (int) v;
                                })))
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0))
                                                .executes(ctx -> modify(ctx, false)))))
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> modify(ctx, true)))))
                        // /debt account <игрок> <forest|depths|blood|fire> <значение>: задать один счёт
                        .then(CommandManager.literal("account")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("kind", StringArgumentType.word())
                                                .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0))
                                                        .executes(DebtMod::setAccount)))))
                        // /debt accounts <игрок>: показать все четыре счёта
                        .then(CommandManager.literal("accounts")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                                    ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                    var d = DebtState.get(ctx.getSource().getServer()).get(t.getUuid());
                                    StringBuilder sb = new StringBuilder(t.getName().getString() + ": ");
                                    for (DebtKind k : DebtKind.values()) sb.append(k.id).append('=').append((int) d.account(k)).append(' ');
                                    sb.append("-> сильнее всего: ").append(d.dominant(t.getRandom()).id);
                                    ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                                    return 1;
                                })))
                        // /debt book <игрок>: положить книгу счетов у кровати прямо сейчас
                        .then(CommandManager.literal("book")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                                    ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                    boolean ok = Ledger.spawn(t, true);
                                    if (!ok) ctx.getSource().sendError(Text.literal(
                                            "Не вышло: пока нечего записывать, или точка возрождения не кровать, или у кровати нет места"));
                                    return ok ? 1 : 0;
                                })))
                        // /debt offer <игрок>: Кредитор предлагает сделку сразу (нужна жила рядом)
                        .then(CommandManager.literal("offer")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                                    ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                    boolean ok = Deal.offer(t, true);
                                    if (!ok) ctx.getSource().sendError(Text.literal(
                                            "Не вышло: нужен обычный мир и алмазная, золотая или изумрудная жила в радиусе 40 блоков"));
                                    return ok ? 1 : 0;
                                })))
                        // /debt summon <игрок> <forest|depths|blood|fire|creditor>: вызвать явление сразу
                        .then(CommandManager.literal("summon")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("kind", StringArgumentType.word())
                                                .executes(DebtMod::summon))))));
    }

    private static int setAccount(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
        DebtKind kind = DebtKind.byId(StringArgumentType.getString(ctx, "kind"));
        if (kind == null) {
            ctx.getSource().sendError(Text.literal("Счёт: forest, depths, blood или fire"));
            return 0;
        }
        double val = DoubleArgumentType.getDouble(ctx, "value");
        DebtState st = DebtState.get(ctx.getSource().getServer());
        var d = st.get(t.getUuid());
        d.accounts[kind.ordinal()] = val;
        double sum = 0;
        for (double a : d.accounts) sum += a;
        d.debt = Math.max(d.debt, sum); // общий долг не меньше суммы счетов
        st.markDirty();
        double now = d.debt;
        ctx.getSource().sendFeedback(() -> Text.literal(t.getName().getString() + ": счёт " + kind.id + " = " + (int) val
                + ", общий долг " + (int) now + ", стадия " + DebtManager.stage(now)), true);
        return (int) val;
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
        String k = StringArgumentType.getString(ctx, "kind");
        boolean ok;
        if (k.equalsIgnoreCase("creditor")) {
            ok = DebtManager.spawnCreditor(t, false);
        } else {
            DebtKind kind = DebtKind.byId(k);
            if (kind == null) {
                ctx.getSource().sendError(Text.literal("Кто: forest, depths, blood, fire или creditor"));
                return 0;
            }
            ok = DebtManager.spawnManifestation(t, kind, true);
        }
        if (!ok) ctx.getSource().sendError(Text.literal("Не получилось: рядом уже есть явление или нет подходящего места"));
        return ok ? 1 : 0;
    }

    private static int modify(CommandContext<ServerCommandSource> ctx, boolean add) throws CommandSyntaxException {
        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
        double val = DoubleArgumentType.getDouble(ctx, "value");
        DebtState st = DebtState.get(ctx.getSource().getServer());
        var d = st.get(t.getUuid());
        d.setTotal(add ? d.debt + val : val);
        st.markDirty();
        double now = d.debt;
        ctx.getSource().sendFeedback(() -> Text.literal(t.getName().getString()
                + ": долг " + (int) now + ", стадия " + DebtManager.stage(now)), true);
        return (int) now;
    }
}
