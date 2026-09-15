package com.lostglade.server;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AncientUkrCreditorSelectionTest {
    public static void main(String[] args) throws Exception {
        var both = AncientUkrCreditorChatSystem.parseModelReply(
                "{\"action\":{\"type\":\"start_repayment\",\"creditNumbers\":[2,1,2]}}");
        check(both.creditNumbers().equals(List.of(2, 1)), "Preserve model selection");
        var changed = AncientUkrCreditorChatSystem.parseModelReply(
                "```json\n{\"action\":{\"type\":\"open_credit\",\"amount\":200}}\n```");
        check(changed.amount() == 200, "Use newly agreed amount");
        var stop = AncientUkrCreditorChatSystem.parseModelReply(
                "{\"action\":{\"type\":\"stop_repayment\"}}");
        check(stop.actionType().equals("stop_repayment"), "Parse stop action");
        reject("{\"action\":{\"type\":\"open_credit\",\"amount\":1.5}}");
        reject("{\"action\":{\"type\":\"open_credit\",\"amount\":999999999999}}");
        reject("{\"action\":{\"type\":\"start_repayment\",\"creditNumbers\":[1,\"oops\"]}}");
        reject("{\"action\":{\"type\":\"start_repayment\",\"creditNumbers\":[1,0]}}");
        reject("{\"action\":{\"type\":\"erase_all_debts\"}}");
        reject("{\"action\":{\"type\":\"offer_credit\",\"amount\":300}}");
        check(!AncientUkrCreditSystem.validPrincipal(null, 1000), "Missing amount");
        check(!AncientUkrCreditSystem.validPrincipal(0, 1000), "Zero amount");
        check(!AncientUkrCreditSystem.validPrincipal(1001, 1000), "Maximum amount");
        check(AncientUkrCreditSystem.validPrincipal(200, 1000), "Valid amount");
        check(AncientUkrCreditorChatSystem.normalizeCurrency("\u0414\u043e 1000 \u0440\u0443\u0431\u043b\u0435\u0439 \u0438\u043b\u0438 300\u20bd")
                .equals("\u0414\u043e 1000\u20bf \u0438\u043b\u0438 300\u20bf"), "Ruble amounts must become tight bitcoin amounts");
        check(AncientUkrCreditorChatSystem.normalizeCurrency("\u041a\u0440\u0435\u0434\u0438\u0442 \u21162: 700 \u0431\u0438\u0442\u043a\u043e\u0438\u043d\u043e\u0432 \u043f\u043e\u0434 7.1%")
                .equals("\u041a\u0440\u0435\u0434\u0438\u0442 \u21162: 700\u20bf \u043f\u043e\u0434 7.1%"), "Do not alter loan IDs or interest rates");
        check(AncientUkrCreditorChatSystem.normalizeCurrency("500 BTC, 250 \u20bf")
                .equals("500\u20bf, 250\u20bf"), "Normalize all explicit bitcoin sums");
        check(AncientUkrCreditorChatSystem.isTerminalMessage("\u0432\u0441\u0451"), "Standalone all-done message must end the conversation");
        check(AncientUkrCreditorChatSystem.isTerminalMessage("\u0432\u0441\u0435!"), "Unaccented all-done message must end the conversation");
        check(!AncientUkrCreditorChatSystem.isTerminalMessage("\u0437\u0430\u043a\u0440\u044b\u0442\u044c \u0432\u0441\u0435 \u043a\u0440\u0435\u0434\u0438\u0442\u044b"),
                "Selecting all loans must not end the conversation");
        check(AncientUkrCreditorChatSystem.actionCorrection("open_credit", "\u0432\u0441\u0451", false)
                != null, "Ambiguous all-done must recheck consent before issuing a second loan");
        for (String all : List.of("\u0432\u0441\u0435", "\u0432\u0441\u0451", "\u043e\u0431\u0430")) {
            for (boolean active : new boolean[]{false, true}) {
                check(AncientUkrCreditorChatSystem.actionCorrection("start_repayment", all, active) == null,
                        "Contextual selection of all loans must reach execution without stopping or finishing");
            }
        }
        check(AncientUkrCreditorChatSystem.actionCorrection("stop_repayment", "\u0432\u0441\u0435", true) == null,
                "Model may stop active reception when context means done giving coins");
        check(AncientUkrCreditorChatSystem.actionCorrection("finish", "\u0432\u0441\u0435", false) == null,
                "Model may finish when context means goodbye");
        check(AncientUkrCreditorChatSystem.actionCorrection("stop_repayment",
                "\u0437\u0430\u043a\u0440\u044b\u0442\u044c \u043a\u0440\u0435\u0434\u0438\u0442", false) != null,
                "Repayment request cannot stop an inactive reception");
        check(AncientUkrCreditSystem.collectorThresholdExceeded(300, BigDecimal.valueOf(301)),
                "Collectors must come when a loan exceeds its principal");
        check(!AncientUkrCreditSystem.collectorThresholdExceeded(300, BigDecimal.valueOf(300)),
                "A loan equal to its principal must not trigger collectors");
        long now = 1_000_000L;
        check(AncientUkrCreditSystem.collectorVisitScheduledTooLate(now, now + 40 * 60_000L, 1.0D),
                "Shortening the interval must replace an old long schedule");
        check(!AncientUkrCreditSystem.collectorVisitScheduledTooLate(now, now + 40_000L, 1.0D),
                "A visit already inside the new interval must keep its deadline");
        verifyReceptionStopsWithoutForgivingDebt();
        verifyIndependentCreditLimits();
        System.out.println("Creditor action parsing, amount validation and repayment lifecycle tests passed");
    }

    @SuppressWarnings("unchecked")
    private static void verifyIndependentCreditLimits() throws Exception {
        Object loader = FabricLoader.getInstance();
        Field configDirectory = field(loader.getClass(), "configDir");
        Object previousDirectory = configDirectory.get(loader);
        configDirectory.set(loader, Path.of("build").toAbsolutePath());
        UUID ownerId = UUID.randomUUID();
        Object store = field(AncientUkrCreditSystem.class, "store").get(null);
        Map<String, Object> borrowers = (Map<String, Object>) field(store.getClass(), "borrowers").get(store);
        Class<?> borrowerClass = Class.forName("com.lostglade.server.AncientUkrCreditSystem$BorrowerState");
        try {
            String loans = "";
            for (int count = 0; count <= 3; count++) {
                if (count > 0) {
                    if (!loans.isEmpty()) loans += ",";
                    loans += "{\"number\":" + count + ",\"principal\":300,\"debt\":900,\"interestRatePercent\":7}";
                }
                borrowers.put(ownerId.toString(), new Gson().fromJson("{\"credits\":[" + loans + "]}", borrowerClass));
                String facts = AncientUkrCreditSystem.buildAiContext(null, ownerId);
                check(facts.contains("activeCreditCount=" + count + "; maxActiveCredits=3; freeCreditSlots=" + (3 - count)),
                        "Context must report actual free loan slots");
                check(facts.contains("maxPrincipalPerCredit=1000; maxNextCreditPrincipal=" + (count < 3 ? 1000 : 0)),
                        "Existing loans and their interest must not reduce the next loan's limit");
                check(facts.contains("canStartRepayment=" + (count > 0) + "; receptionActive=false"),
                        "Existing loans can start repayment even without an active reception");
            }
        } finally {
            borrowers.remove(ownerId.toString());
            configDirectory.set(loader, previousDirectory);
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyReceptionStopsWithoutForgivingDebt() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Object store = field(AncientUkrCreditSystem.class, "store").get(null);
        Map<String, Object> borrowers = (Map<String, Object>) field(store.getClass(), "borrowers").get(store);
        Class<?> borrowerClass = Class.forName("com.lostglade.server.AncientUkrCreditSystem$BorrowerState");
        Object borrower = new Gson().fromJson("{\"credits\":[{\"number\":1,\"principal\":100,\"debt\":108,\"interestRatePercent\":8}]}", borrowerClass);
        borrowers.put(ownerId.toString(), borrower);
        Map<UUID, Object> sessions = (Map<UUID, Object>) field(AncientUkrCreditSystem.class, "REPAYMENTS").get(null);
        Class<?> sessionClass = Class.forName("com.lostglade.server.AncientUkrCreditSystem$RepaymentSession");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(List.class);
        constructor.setAccessible(true);
        try {
            for (int paid : new int[]{0, 12}) {
                Object session = constructor.newInstance(List.of(1));
                field(sessionClass, "totalPaid").setInt(session, paid);
                sessions.put(ownerId, session);
                check(AncientUkrCreditSystem.hasActiveRepayment(ownerId), "Reception must be active before stopping");
                var result = AncientUkrCreditSystem.applyAiAction(null, ownerId, "stop_repayment", null, List.of(), null, "");
                check(!sessions.containsKey(ownerId), "Reception must stop even with debt remaining");
                check(!AncientUkrCreditSystem.hasActiveRepayment(ownerId), "Stopped reception must become inactive");
                check(result.event().contains("accepted=" + paid), "Report actual accepted amount");
                Object credit = ((List<?>) field(borrowerClass, "credits").get(borrower)).getFirst();
                check(new BigDecimal("108").equals(field(credit.getClass(), "debt").get(credit)), "Stopping cannot erase debt");
            }
            sessions.put(ownerId, constructor.newInstance(List.of(1)));
            var finish = AncientUkrCreditSystem.applyAiAction(null, ownerId, "finish", null, List.of(), null, "");
            check(finish.closeCreditor() && !sessions.containsKey(ownerId), "Goodbye also stops reception");
        } finally {
            sessions.remove(ownerId);
            borrowers.remove(ownerId.toString());
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void reject(String json) {
        try {
            AncientUkrCreditorChatSystem.parseModelReply(json);
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Invalid model action accepted: " + json);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
