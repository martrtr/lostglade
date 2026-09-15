package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.item.ModItems;
import com.mojang.brigadier.context.CommandContext;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AncientUkrCreditSystem {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final String FILE_NAME = "lg2-ancient-ukr-credits.json";
    private static final String RACE_ID = "ancient_ukr";
    private static final int DEFAULT_MAX_ACTIVE = 3;
    private static final int DEFAULT_MAX_PRINCIPAL = 1000;
    private static final double DEFAULT_MIN_RATE = 5.0D;
    private static final double DEFAULT_MAX_RATE = 10.0D;
    private static final double DEFAULT_MAX_DEBT_MULTIPLIER = 3.0D;
    private static final double DEFAULT_COLLECTOR_MIN_INTERVAL_MINUTES = 30.0D;
    private static final double DEFAULT_COLLECTOR_MAX_INTERVAL_MINUTES = 60.0D;
    private static final int COLLECTORS_PER_ACTIVE_CREDIT = 3;
    private static final long COLLECTOR_SPAWN_RETRY_MILLIS = 10_000L;
    private static final int BITCOIN_CONTAINER_SCAN_MAX_DEPTH = 8;
    private static final long LOAN_PAYOUT_STACK_INTERVAL_TICKS = 4L;
    private static final String COIN_GLYPH = "\ue981";
    private static final String FALLBACK_COIN = "\u20bf";
    private static final String CREDIT_SCOREBOARD_HEADER_GLYPH = "\uebf2";
    private static final String CREDIT_SCOREBOARD_MIDDLE_GLYPH = "\uebf3";
    private static final String CREDIT_SCOREBOARD_BOTTOM_GLYPH = "\uebf4";
    // Vanilla renders its sidebar background two pixels beyond each side of its
    // measured text width.  The glyph therefore begins two pixels before the
    // text origin and is 160 px wide, while its negative spaces leave vanilla
    // measuring the component as exactly 156 px.
    private static final int CREDIT_SCOREBOARD_CONTENT_WIDTH = 156;
    private static final int CREDIT_SCOREBOARD_GLYPH_RENDER_WIDTH = 160;
    // BitmapProvider adds one pixel to a bitmap glyph's advance.
    private static final int CREDIT_SCOREBOARD_GLYPH_ADVANCE = CREDIT_SCOREBOARD_GLYPH_RENDER_WIDTH + 1;
    private static final int CREDIT_SCOREBOARD_LEFT_OVERFLOW = 2;
    private static final int CREDIT_SCOREBOARD_ENTRY_START = 13;
    private static final String LOAN_PAYOUT_ITEM_TAG = "lg2_ancient_ukr_loan_payout";
    private static final FontDescription COIN_FONT = new FontDescription.Resource(
            Objects.requireNonNull(Identifier.tryParse("lg2:upgrade_tooltip")));
    private static final FontDescription CREDIT_SCOREBOARD_FONT = new FontDescription.Resource(
            Objects.requireNonNull(Identifier.tryParse("lg2:credit_scoreboard")));

    private static CreditStore store = new CreditStore();
    private static final Map<UUID, Objective> CLIENT_OBJECTIVES = new HashMap<>();
    private static final Map<UUID, RepaymentSession> REPAYMENTS = new HashMap<>();
    private static final Map<UUID, Deque<LoanPayout>> LOAN_PAYOUTS = new HashMap<>();
    private static final Set<UUID> CREDIT_OVERLAY_HIDDEN = new HashSet<>();
    private static long lastPeriodicTick = Long.MIN_VALUE;
    private static long lastCollectorTick = Long.MIN_VALUE;
    private static boolean loaded;

    private AncientUkrCreditSystem() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        ServerLifecycleEvents.SERVER_STARTED.register(AncientUkrCreditSystem::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            save(server);
            CLIENT_OBJECTIVES.clear();
            REPAYMENTS.clear();
            LOAN_PAYOUTS.clear();
            CREDIT_OVERLAY_HIDDEN.clear();
            AncientUkrCollectorSystem.clearAll(server, false);
            loaded = false;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> onPlayerJoined(server, handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CLIENT_OBJECTIVES.remove(handler.player.getUUID());
            REPAYMENTS.remove(handler.player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(AncientUkrCreditSystem::tick);
    }

    public static int forceInterestAccrual(CommandContext<CommandSourceStack> context) {
        BigDecimal total = accrueFixedInterest(context.getSource().getServer(), true);
        context.getSource().sendSuccess(() -> Component.literal(
                "\u0414\u043e\u0441\u0440\u043e\u0447\u043d\u043e \u043d\u0430\u0447\u0438\u0441\u043b\u0435\u043d\u043e " + formatAmount(total) + " " + FALLBACK_COIN), true);
        return 1;
    }

    static void onCreditorSpawned(MinecraftServer server, UUID ownerId) {
        REPAYMENTS.remove(ownerId);
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            borrower(ownerId, owner.getGameProfile().name());
            save(server);
            syncScoreboard(owner);
        }
        AncientUkrCreditorChatSystem.beginConversation(server, ownerId);
    }

    static void onCreditorRemoved(UUID ownerId) {
        REPAYMENTS.remove(ownerId);
    }
    static String buildAiContext(MinecraftServer server, UUID ownerId) {
        ServerPlayer owner = server == null ? null : server.getPlayerList().getPlayer(ownerId);
        BorrowerState borrower = store.borrowers.get(ownerId.toString());
        deduplicateCredits(borrower);
        CreditTerms terms = terms();
        StringBuilder out = new StringBuilder("FACTS:\n");
        out.append("nickname=").append(owner == null ? storedNickname(borrower, ownerId) : owner.getGameProfile().name()).append('\n');
        int activeCount = borrower == null ? 0 : borrower.credits.size();
        out.append("activeCreditCount=").append(activeCount)
                .append("; maxActiveCredits=").append(terms.maxActive())
                .append("; freeCreditSlots=").append(Math.max(0, terms.maxActive() - activeCount)).append('\n');
        out.append("maxPrincipalPerCredit=").append(terms.maxPrincipal())
                .append("; maxNextCreditPrincipal=").append(activeCount < terms.maxActive() ? terms.maxPrincipal() : 0)
                .append("; sharedPrincipalLimit=none; existing_debts_do_not_reduce_per_credit_limit=true\n");
        out.append("currentNewCreditHourlyRate=").append(formatPercent(currentRatePercent()))
                .append("%; possibleRateRange=").append(formatPercent(terms.minRate())).append("%-")
                .append(formatPercent(terms.maxRate())).append("%\n");
        out.append("rateRule=new rate changes hourly; each issued loan fixes the rate at issuance; simple hourly interest on principal, offline; cap after charge at ")
                .append(formatAmount(BigDecimal.valueOf(terms.maxDebtMultiplier()))).append("x principal\n");
        if (borrower == null || borrower.credits.isEmpty()) {
            out.append("credits=none\n");
        } else {
            out.append("credits:\n");
            borrower.credits.stream().sorted(Comparator.comparingInt(credit -> credit.number)).forEach(credit ->
                    out.append('#').append(credit.number).append(" principal=").append(credit.principal)
                            .append(" debt=").append(formatAmount(credit.debt)).append(" rate=")
                            .append(formatPercent(credit.interestRatePercent)).append("%\n"));
        }
        RepaymentSession repayment = REPAYMENTS.get(ownerId);
        out.append("canStartRepayment=").append(activeCount > 0)
                .append("; receptionActive=").append(repayment != null).append('\n');
        out.append("repayment=").append(repayment == null ? "none" :
                "credits " + repayment.creditNumbers + ", paid " + repayment.totalPaid).append('\n');
        return out.toString();
    }

    static List<Integer> activeCreditNumbers(UUID ownerId) {
        if (ownerId == null) return List.of();
        BorrowerState borrower = store.borrowers.get(ownerId.toString());
        if (borrower == null) return List.of();
        return borrower.credits.stream()
                .filter(credit -> credit.debt.signum() > 0)
                .map(credit -> credit.number)
                .distinct()
                .sorted()
                .toList();
    }

    static ActionResolution applyAiAction(MinecraftServer server, UUID ownerId, String actionType,
                                          Integer creditNumber, List<Integer> creditNumbers,
                                          Integer amount, String modelReply) {
        String type = actionType == null ? "none" : actionType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "", "none" -> new ActionResolution(modelReply, false);
            case "open_credit" -> openCredit(server, ownerId, amount);
            case "start_repayment" -> startRepayment(server, ownerId, creditNumber, creditNumbers);
            case "continue_repayment" -> continueRepayment(server, ownerId);
            case "stop_repayment" -> stopRepayment(ownerId);
            case "finish" -> finishConversation(ownerId);
            default -> result("action_rejected; reason=unknown_action" );
        };
    }

    static void syncScoreboard(ServerPlayer player) {
        if (player == null || player.connection == null) return;
        UUID playerId = player.getUUID();
        Objective previous = CLIENT_OBJECTIVES.remove(playerId);
        if (previous != null) {
            player.connection.send(new ClientboundSetObjectivePacket(previous, ClientboundSetObjectivePacket.METHOD_REMOVE));
        }
        if (CREDIT_OVERLAY_HIDDEN.contains(playerId)) {
            restoreServerSidebar(player);
            return;
        }
        BorrowerState borrower = store.borrowers.get(playerId.toString());
        deduplicateCredits(borrower);
        if (borrower == null || borrower.credits.isEmpty()) {
            restoreServerSidebar(player);
            return;
        }
        Objective objective = new Objective(new Scoreboard(), objectiveName(playerId), ObjectiveCriteria.DUMMY,
                creditScoreboardTitle(player),
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        CLIENT_OBJECTIVES.put(playerId, objective);
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        List<Credit> credits = borrower.credits.stream().sorted(Comparator.comparingInt(credit -> credit.number)).toList();
        for (int index = 0; index < credits.size(); index++) {
            Credit credit = credits.get(index);
            player.connection.send(new ClientboundSetScorePacket("lg2_credit_" + credit.number, objective.getName(),
                    credits.size() - index, Optional.of(creditLine(player, credit)), Optional.of(BlankFormat.INSTANCE)));
        }
        if (hasCreditScoreboardPack(player)) {
            // A genuine final sidebar row makes the lower corners independent of
            // the number of active credits.  Its score is deliberately lowest so
            // vanilla places it after every debt line.
            player.connection.send(new ClientboundSetScorePacket("lg2_credit_frame_bottom", objective.getName(),
                    0, Optional.of(creditScoreboardFooter()), Optional.of(BlankFormat.INSTANCE)));
        }
    }

    static int toggleCreditOverlay(ServerPlayer player) {
        if (player == null) return 0;
        UUID playerId = player.getUUID();
        boolean hidden;
        if (CREDIT_OVERLAY_HIDDEN.remove(playerId)) {
            hidden = false;
        } else {
            CREDIT_OVERLAY_HIDDEN.add(playerId);
            hidden = true;
        }
        syncScoreboard(player);
        String message = hidden
                ? "\u041a\u0440\u0435\u0434\u0438\u0442\u043d\u044b\u0439 \u043e\u0432\u0435\u0440\u043b\u0435\u0439 \u0441\u043a\u0440\u044b\u0442"
                : "\u041a\u0440\u0435\u0434\u0438\u0442\u043d\u044b\u0439 \u043e\u0432\u0435\u0440\u043b\u0435\u0439 \u0432\u043a\u043b\u044e\u0447\u0451\u043d";
        player.displayClientMessage(Component.literal(message).withStyle(style ->
                style.withColor(hidden ? ChatFormatting.GRAY : ChatFormatting.GOLD).withItalic(false)), true);
        return 1;
    }
    private static void load(MinecraftServer server) {
        lastPeriodicTick = Long.MIN_VALUE;
        lastCollectorTick = Long.MIN_VALUE;
        CLIENT_OBJECTIVES.clear();
        REPAYMENTS.clear();
        LOAN_PAYOUTS.clear();
        AncientUkrCollectorSystem.clearAll(server, false);
        store = new CreditStore();
        Path path = statePath(server);
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                CreditStore loadedStore = GSON.fromJson(reader, CreditStore.class);
                if (loadedStore != null) store = loadedStore;
            } catch (IOException | RuntimeException exception) {
                Lg2.LOGGER.warn("Failed to load Ancient Ukr credits", exception);
            }
        }
        long currentHour = currentEpochHour();
        if (store.rateSeed == 0L) store.rateSeed = ThreadLocalRandom.current().nextLong();
        sanitizeStore(rateForHour(currentHour));
        if (store.lastAccruedEpochHour <= 0L || store.lastAccruedEpochHour > currentHour) {
            store.lastAccruedEpochHour = currentHour;
        }
        loaded = true;
        accrueMissedHours(server, currentHour);
        save(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) onPlayerJoined(server, player);
        Lg2.LOGGER.info("Loaded Ancient Ukr credit state for {} borrowers", store.borrowers.size());
    }

    private static void tick(MinecraftServer server) {
        if (!loaded || server == null) return;
        AncientUkrCollectorSystem.tick(server);
        tickLoanPayouts(server);
        tickRepayments(server);
        long tick = server.overworld().getGameTime();
        if (lastCollectorTick == Long.MIN_VALUE || tick - lastCollectorTick >= 20L) {
            lastCollectorTick = tick;
            tickCollectorVisits(server);
        }
        if (lastPeriodicTick != Long.MIN_VALUE && tick - lastPeriodicTick < 20L) return;
        lastPeriodicTick = tick;
        accrueMissedHours(server, currentEpochHour());
    }

    private static void accrueMissedHours(MinecraftServer server, long currentHour) {
        if (store.lastAccruedEpochHour >= currentHour) return;
        Map<UUID, BigDecimal> notifications = new HashMap<>();
        while (store.lastAccruedEpochHour < currentHour) {
            accrueFixedInterest(notifications);
            store.lastAccruedEpochHour++;
        }
        save(server);
        notifyAccruedInterest(server, notifications);
        syncAllOnlineBorrowers(server);
    }

    private static BigDecimal accrueFixedInterest(MinecraftServer server, boolean notify) {
        Map<UUID, BigDecimal> notifications = new HashMap<>();
        BigDecimal total = accrueFixedInterest(notifications);
        save(server);
        if (notify) notifyAccruedInterest(server, notifications);
        syncAllOnlineBorrowers(server);
        return total;
    }

    private static BigDecimal accrueFixedInterest(Map<UUID, BigDecimal> notifications) {
        BigDecimal multiplier = BigDecimal.valueOf(terms().maxDebtMultiplier());
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BorrowerState> entry : store.borrowers.entrySet()) {
            BigDecimal borrowerTotal = BigDecimal.ZERO;
            for (Credit credit : entry.getValue().credits) {
                BigDecimal cap = BigDecimal.valueOf(credit.principal).multiply(multiplier);
                if (credit.debt.compareTo(cap) >= 0) continue;
                BigDecimal rate = BigDecimal.valueOf(credit.interestRatePercent)
                        .divide(BigDecimal.valueOf(100L), 8, RoundingMode.HALF_UP);
                BigDecimal interest = normalizeMoney(BigDecimal.valueOf(credit.principal).multiply(rate));
                if (interest.signum() <= 0) continue;
                credit.debt = normalizeMoney(credit.debt.add(interest));
                borrowerTotal = borrowerTotal.add(interest);
            }
            if (borrowerTotal.signum() > 0) {
                entry.getValue().pendingInterestNotification = normalizeMoney(
                        entry.getValue().pendingInterestNotification.add(borrowerTotal));
                UUID ownerId = parseUuid(entry.getKey());
                if (ownerId != null) notifications.merge(ownerId, borrowerTotal, BigDecimal::add);
                total = total.add(borrowerTotal);
            }
        }
        return normalizeMoney(total);
    }

    private static void notifyAccruedInterest(MinecraftServer server, Map<UUID, BigDecimal> notifications) {
        boolean clearedPending = false;
        for (UUID ownerId : notifications.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
            BorrowerState borrower = store.borrowers.get(ownerId.toString());
            if (player == null || borrower == null || borrower.pendingInterestNotification.signum() <= 0) continue;
            showInterestNotification(player, borrower.pendingInterestNotification);
            borrower.pendingInterestNotification = BigDecimal.ZERO;
            clearedPending = true;
        }
        if (clearedPending) save(server);
    }

    private static void showPendingInterestNotification(MinecraftServer server, ServerPlayer player) {
        BorrowerState borrower = store.borrowers.get(player.getUUID().toString());
        if (borrower == null || borrower.pendingInterestNotification.signum() <= 0) return;
        showInterestNotification(player, borrower.pendingInterestNotification);
        borrower.pendingInterestNotification = BigDecimal.ZERO;
        save(server);
    }

    private static void showInterestNotification(ServerPlayer player, BigDecimal amount) {
        MutableComponent message = Component.literal("+" + formatAmount(amount)).withStyle(ChatFormatting.RED);
        message.append(coinComponent(player));
        player.displayClientMessage(message, true);
        Holder<SoundEvent> sound = Holder.direct(SoundEvents.EXPERIENCE_ORB_PICKUP);
        Vec3 position = player.position();
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS,
                position.x, position.y, position.z, 0.8F, 1.0F, player.getRandom().nextLong()));
    }
    static boolean validPrincipal(Integer amount, int maximum) {
        return amount != null && amount > 0 && amount <= maximum;
    }

    static boolean hasActiveRepayment(UUID ownerId) {
        return ownerId != null && REPAYMENTS.containsKey(ownerId);
    }

    private static ActionResolution openCredit(MinecraftServer server, UUID ownerId, Integer amount) {
        CreditTerms terms = terms();
        if (!validPrincipal(amount, terms.maxPrincipal())) {
            return result("credit_not_issued; reason=invalid_amount; maximum=" + terms.maxPrincipal());
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId) == null) {
            return result("credit_not_issued; reason=client_or_creditor_unavailable");
        }
        BorrowerState borrower = borrower(ownerId, owner.getGameProfile().name());
        if (borrower.credits.size() >= terms.maxActive()) {
            return result("credit_not_issued; reason=active_credit_limit");
        }
        int number = nextCreditNumber(borrower, terms.maxActive());
        if (number <= 0 || !queueLoanBitcoinPayout(owner, amount)) {
            return result("credit_not_issued; reason=payout_unavailable");
        }
        double rate = currentRatePercent();
        borrower.credits.add(new Credit(number, amount, BigDecimal.valueOf(amount), rate));
        save(server);
        syncScoreboard(owner);
        return result("credit_issued; number=" + number + "; principal=" + amount
                + "; fixedHourlyRate=" + formatPercent(rate) + "; payout=throwing_stacks_to_client");
    }

    private static ActionResolution startRepayment(MinecraftServer server, UUID ownerId,
                                                    Integer singleNumber, List<Integer> requestedNumbers) {
        List<Integer> numbers = new ArrayList<>();
        if (requestedNumbers != null) {
            for (Integer number : requestedNumbers) {
                if (number == null || number <= 0) return result("reception_not_started; reason=invalid_credit_number");
                if (!numbers.contains(number)) numbers.add(number);
            }
        }
        if (numbers.isEmpty() && singleNumber != null) numbers.add(singleNumber);
        if (numbers.isEmpty()) return result("reception_not_started; reason=missing_credit_selection");
        numbers.sort(Integer::compareTo);
        for (Integer number : numbers) {
            if (findCredit(ownerId, number) == null) {
                return result("reception_not_started; reason=unknown_credit; number=" + number);
            }
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || activeCreditorEntity(owner, ownerId) == null) {
            return result("reception_not_started; reason=client_or_creditor_unavailable");
        }
        RepaymentSession existing = REPAYMENTS.get(ownerId);
        if (existing != null && existing.creditNumbers.equals(numbers)) {
            return result("reception_already_active; credits=" + numbers + "; accepted=" + existing.totalPaid);
        }
        REPAYMENTS.put(ownerId, new RepaymentSession(List.copyOf(numbers)));
        return result("reception_started; credits=" + numbers + "; accepted=0; awaiting_physical_bitcoins=true");
    }

    private static ActionResolution continueRepayment(MinecraftServer server, UUID ownerId) {
        RepaymentSession session = REPAYMENTS.get(ownerId);
        return session == null ? result("reception_inactive")
                : result("reception_continues; credits=" + session.creditNumbers + "; accepted=" + session.totalPaid);
    }

    private static ActionResolution stopRepayment(UUID ownerId) {
        RepaymentSession session = REPAYMENTS.remove(ownerId);
        if (session == null) return result("reception_already_inactive; debts_unchanged=true");
        return result("reception_stopped; credits=" + session.creditNumbers + "; accepted=" + session.totalPaid
                + "; remaining_debts_in_FACTS=true; no_debt_forgiven=true");
    }

    private static ActionResolution finishConversation(UUID ownerId) {
        ActionResolution reception = stopRepayment(ownerId);
        return new ActionResolution("conversation_finished; " + reception.event(), true);
    }
    private static void tickRepayments(MinecraftServer server) {
        if (REPAYMENTS.isEmpty()) return;
        Iterator<Map.Entry<UUID, RepaymentSession>> iterator = REPAYMENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RepaymentSession> entry = iterator.next();
            UUID ownerId = entry.getKey();
            RepaymentSession session = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            Entity creditor = activeCreditorEntity(owner, ownerId);
            List<Credit> credits = repaymentCredits(ownerId, session.creditNumbers);
            if (owner == null || creditor == null || credits.isEmpty()) {
                iterator.remove();
                continue;
            }
            int accepted = collectNearbyBitcoins(creditor, owner, credits);
            if (accepted > 0) {
                session.totalPaid += accepted;
                Lg2.LOGGER.info("Ancient Ukr creditor accepted {} bitcoins from {} for credits {}",
                        accepted, owner.getGameProfile().name(), session.creditNumbers);
                BorrowerState borrower = store.borrowers.get(ownerId.toString());
                if (borrower != null) {
                    borrower.credits.removeIf(credit -> credit.debt.signum() <= 0);
                    removeEmptyBorrower(ownerId, borrower);
                }
                save(server);
                syncScoreboard(owner);
            }
            List<Credit> remainingCredits = repaymentCredits(ownerId, session.creditNumbers);
            if (remainingCredits.isEmpty()) {
                iterator.remove();
                AncientUkrCreditorChatSystem.narrateEvent(server, ownerId,
                        "selected_credits_fully_repaid; credits=" + session.creditNumbers
                                + "; accepted=" + session.totalPaid + "; reception_stopped=true");
                continue;
            }

        }
    }

    private static List<Credit> repaymentCredits(UUID ownerId, List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) return List.of();
        List<Credit> credits = new ArrayList<>();
        for (Integer number : numbers) {
            Credit credit = findCredit(ownerId, number);
            if (credit != null && credit.debt.signum() > 0) credits.add(credit);
        }
        return credits;
    }

    private static int collectNearbyBitcoins(Entity creditor, ServerPlayer owner, List<Credit> credits) {
        if (!(creditor.level() instanceof ServerLevel level) || credits.isEmpty()) return 0;
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                creditor.getBoundingBox().inflate(1.0D, 0.5D, 1.0D),
                item -> item.isAlive()
                        && !item.hasPickUpDelay()
                        && item.getAge() >= 2
                        && !item.getTags().contains(LOAN_PAYOUT_ITEM_TAG)
                        && item.getItem().is(ModItems.BITCOIN));
        items.sort(Comparator.comparingDouble(item -> item.distanceToSqr(creditor)));
        int accepted = 0;
        for (ItemEntity item : items) {
            Entity itemOwner = item.getOwner();
            if (itemOwner != null && itemOwner != owner) continue;
            BigDecimal totalDebt = credits.stream()
                    .map(credit -> credit.debt.max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int needed = totalDebt.setScale(0, RoundingMode.CEILING).intValue();
            if (needed <= 0) break;
            ItemStack stack = item.getItem();
            int take = Math.min(stack.getCount(), needed);
            if (take <= 0) continue;
            ItemStack remainder = stack.copy();
            remainder.shrink(take);
            if (remainder.isEmpty()) item.discard(); else item.setItem(remainder);
            playSafeCreditorPickupFeedback(level, creditor);
            distributePaymentEvenly(credits, BigDecimal.valueOf(take));
            accepted += take;
        }
        return accepted;
    }

    private static void playSafeCreditorPickupFeedback(ServerLevel level, Entity creditor) {
        level.playSound(null, creditor.getX(), creditor.getY(), creditor.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                1.6F + level.getRandom().nextFloat() * 0.4F);
    }

    private static void distributePaymentEvenly(List<Credit> credits, BigDecimal payment) {
        int remaining = normalizeMoney(payment).intValueExact();
        List<Credit> unpaid = new ArrayList<>();
        for (Credit credit : credits) {
            if (credit != null && credit.debt.signum() > 0) {
                credit.debt = normalizeMoney(credit.debt);
                unpaid.add(credit);
            }
        }
        int index = 0;
        while (remaining > 0 && !unpaid.isEmpty()) {
            if (index >= unpaid.size()) index = 0;
            Credit credit = unpaid.get(index);
            credit.debt = credit.debt.subtract(BigDecimal.ONE).max(BigDecimal.ZERO);
            remaining--;
            if (credit.debt.signum() <= 0) {
                unpaid.remove(index);
            } else {
                index++;
            }
        }
    }

    static int confiscateAllBitcoinsAndRepay(MinecraftServer server, ServerPlayer owner) {
        if (server == null || owner == null) return 0;
        int confiscated = 0;
        Inventory inventory = owner.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            confiscated += removeAllBitcoins(inventory.getItem(slot), 0);
            if (inventory.getItem(slot).isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
        }
        ItemStack carried = owner.containerMenu == null ? ItemStack.EMPTY : owner.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            confiscated += removeAllBitcoins(carried, 0);
            if (carried.isEmpty()) owner.containerMenu.setCarried(ItemStack.EMPTY);
        }
        inventory.setChanged();
        if (owner.containerMenu != null) owner.containerMenu.broadcastChanges();

        BorrowerState borrower = store.borrowers.get(owner.getUUID().toString());
        if (borrower != null && confiscated > 0) {
            List<Credit> active = borrower.credits.stream()
                    .filter(credit -> credit != null && credit.debt.signum() > 0)
                    .sorted(Comparator.comparingInt(credit -> credit.number))
                    .toList();
            distributePaymentEvenly(active, BigDecimal.valueOf(confiscated));
            borrower.credits.removeIf(credit -> credit.debt.signum() <= 0);
            if (!hasCollectorThresholdDebt(borrower)) borrower.nextCollectorVisitEpochMillis = 0L;
            removeEmptyBorrower(owner.getUUID(), borrower);
            save(server);
            syncScoreboard(owner);
        }
        return confiscated;
    }

    private static int removeAllBitcoins(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(ModItems.BITCOIN)) {
            int removed = stack.getCount();
            stack.setCount(0);
            return removed;
        }
        if (depth >= BITCOIN_CONTAINER_SCAN_MAX_DEPTH) return 0;

        int removed = 0;
        ItemContainerContents container = shulkerContents(stack);
        if (container != null) {
            List<ItemStack> contents = container.stream().map(ItemStack::copy).toList();
            List<ItemStack> updated = new ArrayList<>(contents);
            for (int index = 0; index < updated.size(); index++) {
                removed += removeAllBitcoins(updated.get(index), depth + 1);
                if (updated.get(index).isEmpty()) updated.set(index, ItemStack.EMPTY);
            }
            if (removed > 0) stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(updated));
        }

        BundleContents bundle = bundleContents(stack);
        if (bundle != null) {
            List<ItemStack> updated = new ArrayList<>();
            for (ItemStack item : bundle.itemsCopy()) updated.add(item.copy());
            int bundleRemoved = 0;
            for (ItemStack item : updated) bundleRemoved += removeAllBitcoins(item, depth + 1);
            if (bundleRemoved > 0) {
                BundleContents.Mutable mutable = new BundleContents.Mutable(bundle);
                mutable.clearItems();
                for (ItemStack item : updated) if (!item.isEmpty()) mutable.tryInsert(item);
                stack.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
                removed += bundleRemoved;
            }
        }
        return removed;
    }

    private static ItemContainerContents shulkerContents(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) return null;
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents == null || contents == ItemContainerContents.EMPTY ? null : contents;
    }

    private static BundleContents bundleContents(ItemStack stack) {
        BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents == null || contents.isEmpty() ? null : contents;
    }

    private static void tickCollectorVisits(MinecraftServer server) {
        long now = System.currentTimeMillis();
        CollectorTerms terms = collectorTerms();
        boolean changed = false;
        for (ServerPlayer owner : server.getPlayerList().getPlayers()) {
            BorrowerState borrower = store.borrowers.get(owner.getUUID().toString());
            if (borrower == null || !isAncientUkr(owner) || !hasCollectorThresholdDebt(borrower)) {
                if (borrower != null && borrower.nextCollectorVisitEpochMillis != 0L) {
                    borrower.nextCollectorVisitEpochMillis = 0L;
                    changed = true;
                }
                continue;
            }
            if (borrower.nextCollectorVisitEpochMillis <= 0L
                    || collectorVisitScheduledTooLate(now, borrower.nextCollectorVisitEpochMillis, terms.maxMinutes())) {
                borrower.nextCollectorVisitEpochMillis = now + randomCollectorDelayMillis(terms);
                changed = true;
                continue;
            }
            if (now < borrower.nextCollectorVisitEpochMillis) continue;
            int activeCredits = (int) borrower.credits.stream()
                    .filter(credit -> credit != null && credit.debt.signum() > 0)
                    .count();
            int spawned = AncientUkrCollectorSystem.spawnWave(owner, collectorWaveSize(activeCredits));
            borrower.nextCollectorVisitEpochMillis = now + (spawned > 0
                    ? randomCollectorDelayMillis(terms) : COLLECTOR_SPAWN_RETRY_MILLIS);
            changed = true;
        }
        if (changed) save(server);
    }

    static int collectorWaveSize(int activeCredits) {
        return Math.max(0, activeCredits) * COLLECTORS_PER_ACTIVE_CREDIT;
    }

    private static boolean hasCollectorThresholdDebt(BorrowerState borrower) {
        return borrower.credits.stream().anyMatch(credit -> credit != null
                && collectorThresholdExceeded(credit.principal, credit.debt));
    }

    static boolean collectorThresholdExceeded(int principal, BigDecimal debt) {
        return principal > 0 && debt != null && debt.compareTo(BigDecimal.valueOf(principal)) > 0;
    }

    private static boolean isAncientUkr(ServerPlayer player) {
        return ServerRaceSystem.getRace(player)
                .map(race -> race.id != null && RACE_ID.equalsIgnoreCase(race.id.trim()))
                .orElse(false);
    }

    static boolean collectorVisitScheduledTooLate(long now, long scheduled, double maxMinutes) {
        long maxDelayMillis = Math.max(1L, (long) Math.ceil(maxMinutes * 60_000.0D));
        return scheduled > now && scheduled - now > maxDelayMillis;
    }

    private static long randomCollectorDelayMillis(CollectorTerms terms) {
        double minutes = ThreadLocalRandom.current().nextDouble(terms.minMinutes(), Math.nextUp(terms.maxMinutes()));
        return Math.max(1L, Math.round(minutes * 60_000.0D));
    }

    private static CollectorTerms collectorTerms() {
        RaceAbilityConfig config = null;
        for (PlayerRaceConfig race : RaceConfig.get().races) {
            if (race != null && race.id != null && RACE_ID.equalsIgnoreCase(race.id.trim())) {
                config = race.shnyaga;
                break;
            }
        }
        double min = config == null || config.ancientUkrCollectorMinIntervalMinutes <= 0.0D
                ? DEFAULT_COLLECTOR_MIN_INTERVAL_MINUTES : config.ancientUkrCollectorMinIntervalMinutes;
        double max = config == null || config.ancientUkrCollectorMaxIntervalMinutes <= 0.0D
                ? DEFAULT_COLLECTOR_MAX_INTERVAL_MINUTES : config.ancientUkrCollectorMaxIntervalMinutes;
        return new CollectorTerms(Math.min(min, max), Math.max(min, max));
    }

    private static boolean queueLoanBitcoinPayout(ServerPlayer owner, int amount) {
        if (owner == null || amount <= 0 || !(owner.level() instanceof ServerLevel)) return false;
        Entity creditor = activeCreditorEntity(owner, owner.getUUID());
        if (creditor == null || !creditor.isAlive()) return false;
        MinecraftServer server = owner.level().getServer();
        if (server == null) return false;
        long tick = server.overworld().getGameTime();
        LOAN_PAYOUTS.computeIfAbsent(owner.getUUID(), ignored -> new ArrayDeque<>())
                .addLast(new LoanPayout(amount, tick));
        return true;
    }

    private static void tickLoanPayouts(MinecraftServer server) {
        if (LOAN_PAYOUTS.isEmpty()) return;
        long tick = server.overworld().getGameTime();
        Iterator<Map.Entry<UUID, Deque<LoanPayout>>> iterator = LOAN_PAYOUTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Deque<LoanPayout>> entry = iterator.next();
            Deque<LoanPayout> payouts = entry.getValue();
            LoanPayout payout = payouts.peekFirst();
            if (payout == null) {
                iterator.remove();
                continue;
            }
            if (tick < payout.nextStackTick) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            Entity creditor = activeCreditorEntity(owner, entry.getKey());
            if (owner == null || creditor == null || !creditor.isAlive()) {
                iterator.remove();
                continue;
            }
            int stackSize = Math.min(payout.remaining, ModItems.BITCOIN.getDefaultMaxStackSize());
            if (!throwLoanBitcoinStack(owner, creditor, stackSize)) {
                payout.nextStackTick = tick + 1L;
                continue;
            }
            payout.remaining -= stackSize;
            payout.nextStackTick = tick + LOAN_PAYOUT_STACK_INTERVAL_TICKS;
            if (payout.remaining <= 0) {
                payouts.removeFirst();
                if (payouts.isEmpty()) iterator.remove();
            }
        }
    }

    private static boolean throwLoanBitcoinStack(ServerPlayer owner, Entity creditor, int count) {
        if (count <= 0 || creditor.level() != owner.level() || !(owner.level() instanceof ServerLevel level)) {
            return false;
        }
        double sourceY = creditor.getY() + creditor.getBbHeight() * 0.65D;
        Vec3 source = new Vec3(creditor.getX(), sourceY, creditor.getZ());
        Vec3 target = owner.position().add(0.0D, 0.1D, 0.0D);
        Vec3 direction = target.subtract(source);
        if (direction.lengthSqr() < 1.0E-6D) direction = creditor.getLookAngle();
        Vec3 velocity = direction.normalize().scale(0.28D).add(0.0D, 0.12D, 0.0D);

        ItemEntity item = new ItemEntity(level, source.x, source.y, source.z,
                new ItemStack(ModItems.BITCOIN, count));
        item.setDefaultPickUpDelay();
        item.setThrower(creditor);
        item.addTag(LOAN_PAYOUT_ITEM_TAG);
        item.setTarget(owner.getUUID());
        item.setDeltaMovement(velocity);
        if (!level.addFreshEntity(item)) return false;
        if (creditor instanceof LivingEntity living) living.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }
    private static Entity activeCreditorEntity(ServerPlayer owner, UUID ownerId) {
        if (owner == null || !(owner.level() instanceof ServerLevel level)) return null;
        UUID creditorId = ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId);
        return creditorId == null ? null : level.getEntity(creditorId);
    }

    private static void onPlayerJoined(MinecraftServer server, ServerPlayer player) {
        BorrowerState borrower = store.borrowers.get(player.getUUID().toString());
        if (borrower != null) {
            borrower.nickname = player.getGameProfile().name();
            save(server);
        }
        showPendingInterestNotification(server, player);
        syncScoreboard(player);
    }

    private static void syncAllOnlineBorrowers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (store.borrowers.containsKey(player.getUUID().toString()) || CLIENT_OBJECTIVES.containsKey(player.getUUID())) {
                syncScoreboard(player);
            }
        }
    }

    private static Component creditLine(ServerPlayer player, Credit credit) {
        MutableComponent line = Component.empty();
        if (hasCreditScoreboardPack(player)) {
            line.append(defaultStyled(horizontalAdvance(-CREDIT_SCOREBOARD_LEFT_OVERFLOW)));
            line.append(Component.literal(CREDIT_SCOREBOARD_MIDDLE_GLYPH)
                    .withStyle(style -> style.withColor(0xFFFFFF).withItalic(false).withFont(CREDIT_SCOREBOARD_FONT)));
            line.append(defaultStyled(horizontalAdvance(CREDIT_SCOREBOARD_ENTRY_START
                    - (CREDIT_SCOREBOARD_GLYPH_ADVANCE - CREDIT_SCOREBOARD_LEFT_OVERFLOW))));
            // Reserve one vanilla text glyph after the frame so every dynamic
            // part of the row clears its inner border by a full character.
            line.append(defaultStyled(" "));
        }
        line.append(Component.literal("\u041a\u0440\u0435\u0434\u0438\u0442 \u2116" + credit.number + " ")
                .withStyle(style -> style.withColor(0xDDD4C3).withItalic(false)));
        line.append(Component.literal(formatAmount(credit.debt) + " ")
                .withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)));
        line.append(coinComponent(player));
        return line;
    }

    /**
     * The artwork contains its own title.  Its baseline is chosen so its lower
     * edge meets the first credit row; the enlarged header therefore grows
     * upward instead of covering dynamic sidebar rows below it.
     */
    private static Component creditScoreboardTitle(ServerPlayer player) {
        Component fallback = Component.literal("\u041a\u0440\u0435\u0434\u0438\u0442\u044b")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withItalic(false));
        if (!hasCreditScoreboardPack(player)) {
            return fallback;
        }
        MutableComponent title = Component.empty();
        title.append(defaultStyled(horizontalAdvance(-CREDIT_SCOREBOARD_LEFT_OVERFLOW)));
        title.append(Component.literal(CREDIT_SCOREBOARD_HEADER_GLYPH)
                .withStyle(style -> style.withColor(0xFFFFFF).withItalic(false).withFont(CREDIT_SCOREBOARD_FONT)));
        title.append(defaultStyled(horizontalAdvance(
                CREDIT_SCOREBOARD_CONTENT_WIDTH - (CREDIT_SCOREBOARD_GLYPH_ADVANCE - CREDIT_SCOREBOARD_LEFT_OVERFLOW))));
        return title;
    }

    private static Component creditScoreboardFooter() {
        MutableComponent footer = Component.empty();
        footer.append(defaultStyled(horizontalAdvance(-CREDIT_SCOREBOARD_LEFT_OVERFLOW)));
        footer.append(Component.literal(CREDIT_SCOREBOARD_BOTTOM_GLYPH)
                .withStyle(style -> style.withColor(0xFFFFFF).withItalic(false).withFont(CREDIT_SCOREBOARD_FONT)));
        footer.append(defaultStyled(horizontalAdvance(CREDIT_SCOREBOARD_CONTENT_WIDTH
                - (CREDIT_SCOREBOARD_GLYPH_ADVANCE - CREDIT_SCOREBOARD_LEFT_OVERFLOW))));
        return footer;
    }

    private static boolean hasCreditScoreboardPack(ServerPlayer player) {
        return player != null && PolymerResourcePackUtils.hasMainPack(player);
    }

    private static Component coinComponent(ServerPlayer player) {
        if (PolymerResourcePackUtils.hasMainPack(player)) {
            return Component.literal(COIN_GLYPH).withStyle(style ->
                    style.withColor(0xFFFFFF).withItalic(false).withFont(COIN_FONT));
        }
        return Component.literal(FALLBACK_COIN).withStyle(style -> style.withColor(0xF6B800).withItalic(false));
    }

    private static Component defaultStyled(String text) {
        return Component.literal(text).withStyle(style -> style.withItalic(false));
    }

    private static String horizontalAdvance(int pixels) {
        if (pixels == 0) return "";
        int remaining = pixels;
        int[] values = remaining > 0
                ? new int[]{64, 32, 16, 8, 4, 2, 1}
                : new int[]{-64, -32, -16, -8, -4, -2, -1};
        String[] glyphs = remaining > 0
                ? new String[]{"\ue94d", "\ue94c", "\ue94b", "\ue94a", "\ue949", "\ue948", "\ue947"}
                : new String[]{"\ue940", "\ue941", "\ue942", "\ue943", "\ue944", "\ue945", "\ue946"};
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            int step = values[index];
            while ((remaining > 0 && remaining >= step) || (remaining < 0 && remaining <= step)) {
                result.append(glyphs[index]);
                remaining -= step;
            }
        }
        return result.toString();
    }

    private static void restoreServerSidebar(ServerPlayer player) {
        Objective sidebar = player.level().getServer().getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, sidebar));
    }

    private static String objectiveName(UUID playerId) {
        return "lg2cr_" + playerId.toString().replace("-", "").substring(0, 10);
    }

    private static BorrowerState borrower(UUID ownerId, String nickname) {
        BorrowerState borrower = store.borrowers.computeIfAbsent(ownerId.toString(), ignored -> new BorrowerState());
        if (nickname != null && !nickname.isBlank()) borrower.nickname = nickname;
        deduplicateCredits(borrower);
        return borrower;
    }

    private static void deduplicateCredits(BorrowerState borrower) {
        if (borrower == null || borrower.credits == null || borrower.credits.size() < 2) return;
        Map<Integer, Credit> unique = new LinkedHashMap<>();
        for (Credit credit : borrower.credits) {
            if (credit != null && credit.number > 0) unique.putIfAbsent(credit.number, credit);
        }
        if (unique.size() != borrower.credits.size()) {
            borrower.credits = new ArrayList<>(unique.values());
            borrower.credits.sort(Comparator.comparingInt(credit -> credit.number));
        }
    }

    private static Credit findCredit(UUID ownerId, Integer number) {
        if (ownerId == null || number == null) return null;
        BorrowerState borrower = store.borrowers.get(ownerId.toString());
        if (borrower == null) return null;
        return borrower.credits.stream().filter(credit -> credit.number == number).findFirst().orElse(null);
    }

    private static int nextCreditNumber(BorrowerState borrower, int maxActive) {
        for (int number = 1; number <= maxActive; number++) {
            int candidate = number;
            if (borrower.credits.stream().noneMatch(credit -> credit.number == candidate)) return number;
        }
        return -1;
    }

    private static void removeEmptyBorrower(UUID ownerId, BorrowerState borrower) {
        if (borrower.credits.isEmpty()) store.borrowers.remove(ownerId.toString());
    }

    private static CreditTerms terms() {
        RaceAbilityConfig config = null;
        for (PlayerRaceConfig race : RaceConfig.get().races) {
            if (race != null && race.id != null && RACE_ID.equals(race.id.trim().toLowerCase(Locale.ROOT))) {
                config = race.shnyaga;
                break;
            }
        }
        int maxActive = config == null || config.ancientUkrCreditMaxActive <= 0 ? DEFAULT_MAX_ACTIVE : config.ancientUkrCreditMaxActive;
        int maxPrincipal = config == null || config.ancientUkrCreditMaxPrincipalBitcoins <= 0 ? DEFAULT_MAX_PRINCIPAL : config.ancientUkrCreditMaxPrincipalBitcoins;
        double minRate = config == null || config.ancientUkrCreditMinHourlyPercent <= 0.0D ? DEFAULT_MIN_RATE : config.ancientUkrCreditMinHourlyPercent;
        double maxRate = config == null || config.ancientUkrCreditMaxHourlyPercent <= 0.0D ? DEFAULT_MAX_RATE : config.ancientUkrCreditMaxHourlyPercent;
        double multiplier = config == null || config.ancientUkrCreditMaxDebtMultiplier <= 0.0D ? DEFAULT_MAX_DEBT_MULTIPLIER : config.ancientUkrCreditMaxDebtMultiplier;
        return new CreditTerms(maxActive, maxPrincipal, Math.min(minRate, maxRate), Math.max(minRate, maxRate), multiplier);
    }
    private static double currentRatePercent() {
        return rateForHour(currentEpochHour());
    }

    private static double rateForHour(long epochHour) {
        CreditTerms terms = terms();
        int minTenths = (int) Math.round(terms.minRate() * 10.0D);
        int maxTenths = (int) Math.round(terms.maxRate() * 10.0D);
        int count = Math.max(1, maxTenths - minTenths + 1);
        int offset = (int) Math.floorMod(mix64(store.rateSeed ^ epochHour), count);
        return (minTenths + offset) / 10.0D;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static long currentEpochHour() {
        return ChronoUnit.HOURS.between(Instant.EPOCH, Instant.now());
    }

    private static String formatPercent(double percent) {
        return BigDecimal.valueOf(percent).stripTrailingZeros().toPlainString();
    }

    private static String formatAmount(BigDecimal amount) {
        return normalizeMoney(amount).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal normalizeMoney(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(0, RoundingMode.CEILING);
    }

    private static String storedNickname(BorrowerState borrower, UUID ownerId) {
        return borrower == null || borrower.nickname == null || borrower.nickname.isBlank()
                ? ownerId.toString() : borrower.nickname;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ActionResolution result(String reply) {
        return new ActionResolution(reply, false);
    }

    private static void sanitizeStore(double legacyRatePercent) {
        if (store.borrowers == null) store.borrowers = new LinkedHashMap<>();
        Iterator<Map.Entry<String, BorrowerState>> iterator = store.borrowers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, BorrowerState> entry = iterator.next();
            if (parseUuid(entry.getKey()) == null || entry.getValue() == null) {
                iterator.remove();
                continue;
            }
            BorrowerState borrower = entry.getValue();
            borrower.pendingInterestNotification = normalizeMoney(borrower.pendingInterestNotification);
            if (borrower.credits == null) borrower.credits = new ArrayList<>();
            borrower.credits.removeIf(credit -> credit == null || credit.number <= 0 || credit.principal <= 0
                    || credit.debt == null || credit.debt.signum() <= 0);
            for (Credit credit : borrower.credits) {
                credit.debt = normalizeMoney(credit.debt);
                if (!Double.isFinite(credit.interestRatePercent) || credit.interestRatePercent <= 0.0D) {
                    credit.interestRatePercent = legacyRatePercent;
                }
            }
            deduplicateCredits(borrower);
            if (borrower.credits.isEmpty()) iterator.remove();
        }
    }

    private static void save(MinecraftServer server) {
        if (!loaded || server == null) return;
        Path path = statePath(server);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(store, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            Lg2.LOGGER.warn("Failed to save Ancient Ukr credits", exception);
        }
    }

    private static Path statePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    record ActionResolution(String event, boolean closeCreditor) {
    }

    private record CreditTerms(int maxActive, int maxPrincipal, double minRate,
                               double maxRate, double maxDebtMultiplier) {
    }

    private record CollectorTerms(double minMinutes, double maxMinutes) {
    }

    private static final class RepaymentSession {
        private final List<Integer> creditNumbers;
        private int totalPaid;

        private RepaymentSession(List<Integer> creditNumbers) {
            this.creditNumbers = creditNumbers;
        }
    }

    private static final class LoanPayout {
        private int remaining;
        private long nextStackTick;

        private LoanPayout(int remaining, long nextStackTick) {
            this.remaining = remaining;
            this.nextStackTick = nextStackTick;
        }
    }

    private static final class CreditStore {
        private long rateSeed;
        private long lastAccruedEpochHour;
        private Map<String, BorrowerState> borrowers = new LinkedHashMap<>();
    }

    private static final class BorrowerState {
        private String nickname = "";
        private BigDecimal pendingInterestNotification = BigDecimal.ZERO;
        private List<Credit> credits = new ArrayList<>();
        private long nextCollectorVisitEpochMillis;
    }

    private static final class Credit {
        private int number;
        private int principal;
        private BigDecimal debt = BigDecimal.ZERO;
        private double interestRatePercent;

        private Credit() {
        }

        private Credit(int number, int principal, BigDecimal debt, double interestRatePercent) {
            this.number = number;
            this.principal = principal;
            this.debt = debt;
            this.interestRatePercent = interestRatePercent;
        }
    }
}
