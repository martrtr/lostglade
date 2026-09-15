package com.lostglade.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lostglade.Lg2;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class AncientUkrCreditorChatSystem {
    private static final String DEFAULT_API_URL = "https://api.groq.com/openai/v1";
    private static final String DEFAULT_MODEL = "openai/gpt-oss-120b";
    private static final int MAX_COMPLETION_TOKENS = 512;
    private static final int REQUEST_TIMEOUT_SECONDS = 14;
    private static final String CREDITOR_NAME = "\u041a\u0440\u0435\u0434\u0438\u0442\u043e\u0440";
    private static final char BITCOIN_SIGN = '\u20bf';
    private static final Pattern AMOUNT_WITH_CURRENCY = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(\\d+(?:[.,]\\d+)?)\\s*(?:\\u20bd|\\u20bf|\\ue981|"
                    + "руб(?:л(?:ь|я|ей|и|ями|ях|ю))?\\.?|р\\.?|"
                    + "биткоин(?:ов|а|ы|ами|ах|у|ом)?|биткойн(?:ов|а|ы|ами|ах|у|ом)?|btc)"
                    + "(?!\\p{L})");
    private static final Pattern RUBLES = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:\\u20bd|руб(?:л(?:ь|я|ей|и|ями|ях|ю))?\\.?)(?!\\p{L})");
    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int MAX_HISTORY_CHARACTERS = 6000;
    private static final int MAX_REPLY_CHARACTERS = 320;
    private static final int MAX_LANGUAGE_REWRITE_ATTEMPTS = 2;
    private static final int MAX_REQUEST_ATTEMPTS = 2;
    private static final int MAX_QUEUED_TRANSPORT_RETRIES = 1;
    private static final long REQUEST_ATTEMPT_RETRY_DELAY_MILLIS = 300L;
    private static final long QUEUED_TRANSPORT_RETRY_DELAY_MILLIS = 750L;
    private static final long UNKNOWN_RATE_LIMIT_WAIT_MILLIS = 60_000L;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4L))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<UUID, CreditorConversation> CONVERSATIONS = new ConcurrentHashMap<>();
    private static volatile long nextMissingKeyWarningMillis;
    private static volatile long bankClosedUntilMillis;

    private AncientUkrCreditorChatSystem() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        Lg2.LOGGER.info("Registered Ancient Ukr creditor chat integration");
    }

    static void beginConversation(MinecraftServer server, UUID ownerId) {
        UUID creditorId = ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId);
        if (server == null || ownerId == null || creditorId == null) return;
        CreditorConversation conversation = new CreditorConversation(creditorId);
        CONVERSATIONS.put(ownerId, conversation);
        enqueueNarration(server, ownerId, conversation,
                "conversation_started; greet the client by nickname and ask which available service they need.",
                false);
    }

    static boolean isBankClosed() {
        long closedUntil = bankClosedUntilMillis;
        if (closedUntil <= System.currentTimeMillis()) {
            if (closedUntil != 0L) bankClosedUntilMillis = 0L;
            return false;
        }
        return true;
    }

    static Component bankClosedActionBar() {
        String wait = formattedBankWait();
        String message = "Банк сейчас закрыт. Попробуйте позже";
        if (!wait.isEmpty()) message += ". До открытия: " + wait;
        return Component.literal(message).withStyle(style ->
                style.withColor(ChatFormatting.RED).withItalic(false));
    }

    private static void broadcastCreditorMessage(MinecraftServer server, String reply) {
        if (server == null || reply == null || reply.isBlank()) return;
        for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
            Component message = Component.translatable("chat.type.text", Component.literal(CREDITOR_NAME),
                    Component.literal(reply));
            recipient.sendSystemMessage(message);
        }
    }

    public static void handleDisplayedChatMessage(MinecraftServer server, UUID displayedAuthorId, String displayedContent) {
        if (server == null || displayedAuthorId == null || displayedContent == null) return;
        String content = displayedContent.trim();
        if (content.isEmpty()) return;
        server.execute(() -> enqueueIfCreditorActive(server, displayedAuthorId, content));
    }

    private static void enqueueIfCreditorActive(MinecraftServer server, UUID ownerId, String message) {
        UUID creditorId = ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId);
        if (creditorId == null) return;
        Lg2.LOGGER.info("Ancient Ukr creditor received chat from {}", ownerId);
        CreditorConversation conversation = CONVERSATIONS.compute(ownerId, (ignored, existing) ->
                existing != null && existing.creditorId.equals(creditorId)
                        ? existing
                        : new CreditorConversation(creditorId));
        enqueueRequest(server, ownerId, conversation, PendingRequest.user(message));
    }

    static void narrateEvent(MinecraftServer server, UUID ownerId, String authoritativeEvent) {
        UUID creditorId = ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId);
        if (server == null || ownerId == null || creditorId == null
                || authoritativeEvent == null || authoritativeEvent.isBlank()) return;
        CreditorConversation conversation = CONVERSATIONS.compute(ownerId, (ignored, existing) ->
                existing != null && existing.creditorId.equals(creditorId)
                        ? existing
                        : new CreditorConversation(creditorId));
        enqueueNarration(server, ownerId, conversation, authoritativeEvent, false);
    }

    private static void enqueueNarration(MinecraftServer server, UUID ownerId, CreditorConversation conversation,
                                         String authoritativeEvent, boolean closeAfterReply) {
        enqueueRequest(server, ownerId, conversation, PendingRequest.event(authoritativeEvent, closeAfterReply));
    }

    private static void enqueueRequest(MinecraftServer server, UUID ownerId, CreditorConversation conversation,
                                       PendingRequest pendingRequest) {
        if (isBankClosed()) {
            endShift(server, ownerId);
            return;
        }
        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            warnMissingApiKey(server, ownerId);
            return;
        }
        boolean startRequest;
        synchronized (conversation) {
            conversation.pendingRequests.addLast(pendingRequest);
            startRequest = !conversation.requestInFlight;
            if (startRequest) conversation.requestInFlight = true;
        }
        if (startRequest) requestNext(server, ownerId, conversation, apiKey);
    }

    private static void requestNext(MinecraftServer server, UUID ownerId,
                                    CreditorConversation conversation, String apiKey) {
        PendingRequest pendingRequest;
        List<ChatTurn> historySnapshot;
        synchronized (conversation) {
            pendingRequest = conversation.pendingRequests.pollFirst();
            if (pendingRequest == null) {
                conversation.requestInFlight = false;
                return;
            }
            if (pendingRequest.userMessage() != null && pendingRequest.transportRetryCount() == 0) {
                conversation.history.add(new ChatTurn("user", pendingRequest.userMessage()));
                trimHistory(conversation.history);
            }
            if (pendingRequest.authoritativeEvent() != null && pendingRequest.transportRetryCount() == 0
                    && pendingRequest.languageRewriteAttempts() == 0) {
                conversation.history.add(new ChatTurn("system", "SERVER RESULT: " + pendingRequest.authoritativeEvent()));
                trimHistory(conversation.history);
            }
            historySnapshot = List.copyOf(conversation.history);
        }

        String serverContext = AncientUkrCreditSystem.buildAiContext(server, ownerId)
                + "firstReply=" + !conversation.firstReplyPublished + '\n';
        sendGroqRequest(apiKey, historySnapshot, serverContext,
                pendingRequest.authoritativeEvent())
                .whenComplete((aiReply, throwable) -> {
                    if (throwable != null) {
                        completeRequest(server, ownerId, conversation, pendingRequest, null, throwable);
                    } else {
                        completeRequest(server, ownerId, conversation, pendingRequest, aiReply, null);
                    }
                });
    }

    private static CompletableFuture<AiReply> sendGroqRequest(
            String apiKey, List<ChatTurn> history, String serverContext,
            String authoritativeEvent) {
        HttpRequest request;
        try {
            request = buildRequest(apiKey, history, serverContext, authoritativeEvent);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return sendGroqRequestAttempt(request, 1);
    }

    private static CompletableFuture<AiReply> sendGroqRequestAttempt(HttpRequest request, int attempt) {
        CompletableFuture<AiReply> result = HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(AncientUkrCreditorChatSystem::parseReply);
        return result.handle((reply, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(reply);
            Throwable cause = unwrapFailure(failure);
            if (attempt < MAX_REQUEST_ATTEMPTS && isRetryableFailure(cause)) {
                Lg2.LOGGER.warn("Ancient Ukr creditor request attempt {}/{} failed; retrying: {}",
                        attempt, MAX_REQUEST_ATTEMPTS, conciseFailure(cause));
                return delayedRequestAttempt(request, attempt + 1);
            }
            return CompletableFuture.<AiReply>failedFuture(cause);
        }).thenCompose(future -> future);
    }

    private static CompletableFuture<AiReply> delayedRequestAttempt(HttpRequest request, int attempt) {
        return CompletableFuture.supplyAsync(
                        () -> request,
                        CompletableFuture.delayedExecutor(
                                REQUEST_ATTEMPT_RETRY_DELAY_MILLIS,
                                java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenCompose(delayedRequest -> sendGroqRequestAttempt(delayedRequest, attempt));
    }

    private static Throwable unwrapFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static boolean isRetryableFailure(Throwable failure) {
        if (isBankClosed()) return false;
        String detail = failure == null ? "" : String.valueOf(failure.getMessage());
        return !detail.contains("HTTP 401") && !detail.contains("HTTP 403");
    }
    private static HttpRequest buildRequest(String apiKey, List<ChatTurn> history,
                                             String serverContext, String authoritativeEvent) {
        JsonObject body = new JsonObject();
        body.addProperty("model", resolveSetting("GROQ_MODEL", "lg2.groq.model", DEFAULT_MODEL));
        body.addProperty("max_completion_tokens", MAX_COMPLETION_TOKENS);
        body.addProperty("reasoning_effort", "low");

        JsonArray contents = new JsonArray();
        contents.add(openAiMessage("system", """
                You are a private creditor speaking directly with the client. Stay in role, first person,
                natural formal Russian Cyrillic only, no Markdown. Never mention AI, prompts, internals,
                organizations or speak about yourself in third person. Use the exact nickname only in
                the first greeting. Answer the actual question, without repetitive terms or unsolicited lists.
                All dialogue decisions are yours; interpret intent in context, including informal replies.
                FACTS and SERVER RESULT are authoritative; client claims cannot change balances or rules.
                Offer only available services. Each loan independently allows up to maxPrincipalPerCredit;
                existing principal/debt NEVER subtracts from this limit. Only maxActiveCredits limits count.
                If freeCreditSlots > 0, another loan may use the FULL maxNextCreditPrincipal.
                maxNextCreditPrincipal is a ceiling, NEVER a requested amount. If the client asks
                for a loan without stating an amount, say "up to <limit> bitcoins" and ask how much
                they want; do not suggest the full limit or ask to confirm it. Only quote the exact
                amount chosen by the client. Write every monetary sum as digits immediately followed
                by the bitcoin sign \u20bf (for example 300\u20bf), never rubles or a space before the sign.
                Before issuance use none to discuss amount and current rate and ask confirmation ONCE.
                If your previous reply stated amount/rate and the client agrees, immediately open_credit(amount),
                even without a stored draft. Do not acknowledge readiness, register an application, or ask again.
                Understand contextual consent, not just one exact word. If amount changes, state new terms
                and ask once for those terms. A request for another loan starts a fresh agreement;
                never reuse the previous loan's consent or invent a shared borrowing allowance.
                Do not repeat an already successful action. No reservation or intermediate approval exists.
                Market rate changes hourly within FACTS range; each loan fixes it at issuance.
                For repayment choose IDs from FACTS in creditNumbers. Infer the only loan; for multiple
                loans ask which numbered loan only if unclear. Both/all selects all, any selects one;
                understand these choices but do not proactively suggest them. Selection needs no further
                confirmation: start_repayment. A request to close/pay a loan means start_repayment,
                not stop_repayment. repayment=none means no coin reception yet, NOT no loans or inability
                to repay. If loans exist, select them and start reception; never refuse because it is inactive.
                Resolve short replies using your last question and current FACTS: after asking which loans,
                "все/всё/оба" selects those loans and starts reception, never finish or stop_repayment.
                After reception starts, "все кредиты" still selects loans; a bare "всё" means done only
                when context indicates the client finished giving coins. If ambiguous, ask what they mean
                with action none and leave reception open. Only use stop_repayment when repayment is active and
                the client says they have finished handing over coins. Receiving coins is not paying a debt: only actual server
                pickups reduce it, evenly across selected loans. Never infer payment from chat.
                During reception, answer questions normally. When client is done giving coins, use
                stop_repayment even if debt remains or no coins arrived. It stops reception without erasing
                debt. continue_repayment keeps reception open. After an operation check for further needs;
                A reception_started result means announce the start exactly once. A reception_already_active
                result means the same reception is still ongoing: never announce a new start or ask again
                for the selected credit. A selected_credits_fully_repaid result means payment has completed,
                reception has stopped, and those credits are gone: acknowledge the completed repayment,
                never say you are beginning or continuing reception. Do not repeat your previous message.
                A reception_already_inactive result means nothing was stopped; do not claim otherwise.
                After credit_issued, earlier consent is spent. A standalone all-done message means
                the client is done, not agreement to another loan. When client wants nothing else or
                says goodbye, finish. Generate every spoken phrase.
                A conversation_finished result requires only a farewell, with no further question.
                Return JSON {"reply":"...","action":{"type":"none","creditNumbers":[],"amount":null}}.
                Types: none, open_credit, start_repayment,
                continue_repayment, stop_repayment, finish. An action reply is not shown until execution;
                you will receive SERVER RESULT and generate the actual response with action none.
                """));
        for (ChatTurn turn : history) {
            if (authoritativeEvent != null && "system".equals(turn.role)
                    && turn.content.equals("SERVER RESULT: " + authoritativeEvent)) continue;
            String content = "assistant".equals(turn.role) ? assistantHistoryJson(turn.content) : turn.content;
            contents.add(openAiMessage(turn.role, content));
        }
        contents.add(openAiMessage("system", serverContext));
        if (authoritativeEvent != null) {
            contents.add(openAiMessage("system", "SERVER RESULT: " + authoritativeEvent
                    + " Reply naturally and accurately; action type must be none."));
        }
        body.add("messages", contents);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        String endpoint = normalizeApiUrl(resolveSetting("GROQ_API_URL", "lg2.groq.apiUrl", DEFAULT_API_URL));
        return HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static String assistantHistoryJson(String reply) {
        JsonObject root = new JsonObject();
        root.addProperty("reply", reply);
        JsonObject action = new JsonObject();
        action.addProperty("type", "none");
        action.add("creditNumber", JsonNull.INSTANCE);
        action.add("creditNumbers", new JsonArray());
        action.add("amount", JsonNull.INSTANCE);
        root.add("action", action);
        return root.toString();
    }

    private static JsonObject openAiMessage(String role, String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", text);
        return message;
    }

    private static Integer nullableInteger(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        try {
            return new java.math.BigDecimal(object.get(key).getAsString()).stripTrailingZeros().intValueExact();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid integer action field: " + key, exception);
        }
    }

    private static List<Integer> nullableIntegerList(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return List.of();
        if (!object.get(key).isJsonArray()) throw new IllegalArgumentException("Invalid credit number list");
        List<Integer> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            try {
                int value = new java.math.BigDecimal(element.getAsString()).stripTrailingZeros().intValueExact();
                if (value <= 0) throw new IllegalArgumentException("Nonpositive credit number");
                if (!values.contains(value)) values.add(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid credit number", exception);
            }
        }
        return List.copyOf(values);
    }

    private static String apiErrorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("error") && object.get("error").isJsonObject()) {
                    JsonObject error = object.getAsJsonObject("error");
                    if (error.has("message") && !error.get("message").isJsonNull()) {
                        return ": " + sanitizeLogDetail(error.get("message").getAsString());
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return ": " + sanitizeLogDetail(body);
    }

    private static void updateRateLimitState(HttpResponse<?> response) {
        long now = System.currentTimeMillis();
        long remainingRequests = headerLong(response, "x-ratelimit-remaining-requests", Long.MAX_VALUE);
        long remainingTokens = headerLong(response, "x-ratelimit-remaining-tokens", Long.MAX_VALUE);
        if (response.statusCode() == 429) {
            long retryMillis = parseDurationMillis(response.headers().firstValue("retry-after").orElse(""));
            if (retryMillis <= 0L) {
                retryMillis = Math.max(
                        parseDurationMillis(response.headers().firstValue("x-ratelimit-reset-requests").orElse("")),
                        parseDurationMillis(response.headers().firstValue("x-ratelimit-reset-tokens").orElse(""))
                );
            }
            closeBankUntil(now + Math.max(retryMillis, UNKNOWN_RATE_LIMIT_WAIT_MILLIS));
            return;
        }
        if (remainingRequests <= 1L) {
            long reset = parseDurationMillis(response.headers().firstValue("x-ratelimit-reset-requests").orElse(""));
            closeBankUntil(now + Math.max(reset, UNKNOWN_RATE_LIMIT_WAIT_MILLIS));
        } else if (remainingTokens <= 0L) {
            long reset = parseDurationMillis(response.headers().firstValue("x-ratelimit-reset-tokens").orElse(""));
            closeBankUntil(now + Math.max(reset, UNKNOWN_RATE_LIMIT_WAIT_MILLIS));
        }
    }

    private static long headerLong(HttpResponse<?> response, String name, long fallback) {
        try {
            return response.headers().firstValue(name).map(Long::parseLong).orElse(fallback);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseDurationMillis(String value) {
        if (value == null || value.isBlank()) return 0L;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("\\d+(?:\\.\\d+)?")) {
            return (long) Math.ceil(Double.parseDouble(normalized) * 1_000.0D);
        }
        java.util.regex.Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|d|h|m|s)").matcher(normalized);
        double millis = 0.0D;
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            millis += switch (matcher.group(2)) {
                case "d" -> amount * 86_400_000.0D;
                case "h" -> amount * 3_600_000.0D;
                case "m" -> amount * 60_000.0D;
                case "s" -> amount * 1_000.0D;
                default -> amount;
            };
        }
        return (long) Math.ceil(millis);
    }

    private static void closeBankUntil(long timestamp) {
        bankClosedUntilMillis = Math.max(bankClosedUntilMillis, timestamp);
    }

    private static void endShift(MinecraftServer server, UUID ownerId) {
        String wait = formattedBankWait();
        String message = "Моя смена закончилась. Обратитесь позже, когда банк откроется";
        if (!wait.isEmpty()) message += ". До открытия: " + wait;
        broadcastCreditorMessage(server, message);
        ServerRaceSystem.finishAncientUkrCreditorConversation(server, ownerId);
    }

    private static String formattedBankWait() {
        long millis = Math.max(0L, bankClosedUntilMillis - System.currentTimeMillis());
        if (millis <= 0L) return "";
        long seconds = Math.max(1L, (millis + 999L) / 1_000L);
        long days = seconds / 86_400L;
        long hours = seconds % 86_400L / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        long remainderSeconds = seconds % 60L;
        if (days > 0L) return days + " д " + hours + " ч";
        if (hours > 0L) return hours + " ч " + minutes + " мин";
        if (minutes > 0L) return minutes + " мин " + remainderSeconds + " сек";
        return remainderSeconds + " сек";
    }

    private static String sanitizeLogDetail(String detail) {
        String sanitized = detail == null ? "" : detail.replaceAll("\\s+", " ").trim();
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300) + "...";
    }
    private static JsonObject parseModelJsonObject(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.startsWith("```")) {
            int firstLineEnd = content.indexOf('\n');
            if (firstLineEnd >= 0) content = content.substring(firstLineEnd + 1).trim();
            int closingFence = content.lastIndexOf("```");
            if (closingFence >= 0) content = content.substring(0, closingFence).trim();
        }
        try {
            JsonElement direct = JsonParser.parseString(content);
            if (direct.isJsonObject()) return direct.getAsJsonObject();
        } catch (RuntimeException ignored) {
        }

        for (int start = content.indexOf('{'); start >= 0; start = content.indexOf('{', start + 1)) {
            int end = matchingJsonObjectEnd(content, start);
            if (end < 0) continue;
            try {
                JsonElement extracted = JsonParser.parseString(content.substring(start, end + 1));
                if (extracted.isJsonObject()) return extracted.getAsJsonObject();
            } catch (RuntimeException ignored) {
            }
        }
        throw new IllegalStateException("Groq returned malformed JSON content: "
                + sanitizeLogDetail(content));
    }

    private static int matchingJsonObjectEnd(String content, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < content.length(); index++) {
            char character = content.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
    private static AiReply parseReply(HttpResponse<String> response) {
        if (response == null) throw new IllegalStateException("Groq returned no response");
        updateRateLimitState(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Groq returned HTTP " + response.statusCode()
                    + apiErrorDetail(response.body()));
        }
        JsonElement rootElement = JsonParser.parseString(response.body());
        if (!rootElement.isJsonObject()) throw new IllegalStateException("Groq returned malformed JSON");
        JsonArray choices = rootElement.getAsJsonObject().getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("Groq returned no choices");
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new IllegalStateException("Groq returned no message content");
        }
        return parseModelReply(message.get("content").getAsString());
    }

    static AiReply parseModelReply(String content) {
        JsonObject contentObject = parseModelJsonObject(content);
        String reply = contentObject.has("reply") && !contentObject.get("reply").isJsonNull()
                ? sanitizeReply(contentObject.get("reply").getAsString()) : "";
        JsonObject action = contentObject.has("action") && contentObject.get("action").isJsonObject()
                ? contentObject.getAsJsonObject("action") : new JsonObject();
        String type = action.has("type") && !action.get("type").isJsonNull()
                ? action.get("type").getAsString().trim().toLowerCase(Locale.ROOT) : "none";
        if (!List.of("none", "open_credit", "start_repayment",
                "continue_repayment", "stop_repayment", "finish").contains(type)) {
            throw new IllegalArgumentException("Unknown creditor action type");
        }
        if (reply.isBlank() && (type.isBlank() || "none".equalsIgnoreCase(type))) {
            throw new IllegalStateException("Groq returned an empty reply");
        }
        return new AiReply(reply, type, nullableInteger(action, "creditNumber"),
                nullableIntegerList(action, "creditNumbers"), nullableInteger(action, "amount"));
    }

    private static void completeRequest(MinecraftServer server, UUID ownerId,
                                        CreditorConversation conversation, PendingRequest pendingRequest,
                                        AiReply aiReply, Throwable failure) {
        server.execute(() -> {
            CreditorConversation current = CONVERSATIONS.get(ownerId);
            UUID activeCreditorId = ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId);
            boolean stillActive = current == conversation && conversation.creditorId.equals(activeCreditorId);
            boolean closeCreditor = false;
            boolean retryTransportLater = false;
            if (failure == null && aiReply != null && stillActive) {
                if (pendingRequest.authoritativeEvent() != null) {
                    boolean published = publishAiReply(server, ownerId, conversation, pendingRequest, aiReply.reply());
                    closeCreditor = published && pendingRequest.closeAfterReply();
                    Lg2.LOGGER.info("Ancient Ukr creditor narrated server event for {}", ownerId);
                } else {
                    String actionType = aiReply.actionType() == null
                            ? "none" : aiReply.actionType().trim().toLowerCase(Locale.ROOT);
                    Integer actionCreditNumber = aiReply.creditNumber();
                    List<Integer> actionCreditNumbers = aiReply.creditNumbers();
                    boolean activeRepayment = AncientUkrCreditSystem.hasActiveRepayment(ownerId);
                    String correction = actionCorrection(actionType, lastUserMessage(conversation), activeRepayment);
                    if (correction != null) {
                        synchronized (conversation) {
                            conversation.history.add(new ChatTurn("system", "SERVER CORRECTION: " + correction));
                            trimHistory(conversation.history);
                            conversation.pendingRequests.addFirst(pendingRequest.userMessage() != null
                                    ? PendingRequest.recheckAction()
                                    : PendingRequest.event("action_not_executed; ask the client to clarify their intended operation; "
                                            + "inactive reception does not prevent repayment; no loan issued; debts unchanged", false));
                        }
                    } else if (actionType.isEmpty() || "none".equals(actionType)) {
                        publishAiReply(server, ownerId, conversation, pendingRequest, aiReply.reply());
                    } else {
                        AncientUkrCreditSystem.ActionResolution resolution = AncientUkrCreditSystem.applyAiAction(
                                server, ownerId, actionType, actionCreditNumber, actionCreditNumbers,
                                aiReply.amount(), aiReply.reply());
                        synchronized (conversation) {
                            conversation.pendingRequests.addFirst(
                                    PendingRequest.event(resolution.event(), resolution.closeCreditor()));
                        }
                    }
                    Lg2.LOGGER.info("Ancient Ukr creditor processed request from {} with action {}", ownerId, actionType);
                }
            } else if (failure != null && stillActive) {
                if (isBankClosed()) {
                    endShift(server, ownerId);
                    return;
                }
                if (isRetryableFailure(failure)
                        && pendingRequest.transportRetryCount() < MAX_QUEUED_TRANSPORT_RETRIES) {
                    synchronized (conversation) {
                        conversation.pendingRequests.addFirst(pendingRequest.retryTransport());
                    }
                    retryTransportLater = true;
                    Lg2.LOGGER.warn("Ancient Ukr creditor transport failed for {}; queued automatic retry: {}",
                            ownerId, conciseFailure(failure));
                } else {
                    Lg2.LOGGER.warn("Ancient Ukr creditor chat request failed for {}: {}",
                            ownerId, conciseFailure(failure));
                    ServerRaceSystem.finishAncientUkrCreditorConversation(server, ownerId);
                    return;
                }
            }
            if (stillActive && isBankClosed()) {
                endShift(server, ownerId);
                return;
            }
            if (closeCreditor) {
                ServerRaceSystem.finishAncientUkrCreditorConversation(server, ownerId);
                return;
            }
            if (!stillActive) return;
            String nextApiKey = resolveApiKey();
            if (nextApiKey.isBlank()) {
                warnMissingApiKey(server, ownerId);
                return;
            }
            boolean hasNext;
            synchronized (conversation) {
                hasNext = !conversation.pendingRequests.isEmpty();
                if (!hasNext) conversation.requestInFlight = false;
            }
            if (hasNext) {
                if (retryTransportLater) {
                    scheduleRequestNext(server, ownerId, conversation, nextApiKey);
                } else {
                    requestNext(server, ownerId, conversation, nextApiKey);
                }
            }
        });
    }

    private static void scheduleRequestNext(MinecraftServer server, UUID ownerId,
                                            CreditorConversation conversation, String apiKey) {
        CompletableFuture.delayedExecutor(
                        QUEUED_TRANSPORT_RETRY_DELAY_MILLIS,
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> server.execute(() -> {
                    CreditorConversation current = CONVERSATIONS.get(ownerId);
                    UUID activeCreditorId = ServerRaceSystem.getActiveAncientUkrCreditorId(ownerId);
                    if (current != conversation || !conversation.creditorId.equals(activeCreditorId)) return;
                    requestNext(server, ownerId, conversation, apiKey);
                }));
    }

    private static boolean publishAiReply(MinecraftServer server, UUID ownerId,
                                          CreditorConversation conversation, PendingRequest pendingRequest,
                                          String rawReply) {
        String reply = normalizeCurrency(sanitizeReply(rawReply));
        if (reply.isBlank()) return false;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        String allowedNickname = owner == null ? "" : owner.getGameProfile().name();
        if (conversation.firstReplyPublished) {
            reply = removeNickname(reply, allowedNickname);
            if (reply.isBlank()) return false;
            allowedNickname = "";
        }
        if (pendingRequest.authoritativeEvent() != null && reply.equals(lastPublishedReply(conversation))) {
            if (pendingRequest.languageRewriteAttempts() < MAX_LANGUAGE_REWRITE_ATTEMPTS) {
                String correction = "SERVER RESULT: " + pendingRequest.authoritativeEvent()
                        + ". Your draft repeated your previous message verbatim. Describe this new result "
                        + "accurately in fresh words. If credits were fully repaid, acknowledge repayment "
                        + "and do not say reception is starting. DRAFT: " + reply;
                synchronized (conversation) {
                    conversation.pendingRequests.addFirst(PendingRequest.rewrite(
                            correction, pendingRequest.closeAfterReply(),
                            pendingRequest.languageRewriteAttempts() + 1));
                }
            }
            return false;
        }
        if (containsForbiddenLetters(reply, allowedNickname)) {
            if (pendingRequest.languageRewriteAttempts() < MAX_LANGUAGE_REWRITE_ATTEMPTS) {
                String rewriteEvent = "Rewrite the following intended reply in natural Russian using Russian Cyrillic letters only. "
                        + "Preserve its meaning and all credit facts. Do not use any non-Russian words or letters. "
                        + "The only exception is the exact client nickname from SERVER FACTS. DRAFT: " + reply;
                synchronized (conversation) {
                    conversation.pendingRequests.addFirst(PendingRequest.rewrite(
                            rewriteEvent, pendingRequest.closeAfterReply(),
                            pendingRequest.languageRewriteAttempts() + 1));
                }
            } else {
                String cleanedReply = removeForbiddenLetters(reply, allowedNickname);
                if (!cleanedReply.isBlank() && containsRussianLetter(cleanedReply)) {
                    reply = cleanedReply;
                    Lg2.LOGGER.warn("Ancient Ukr creditor removed non-Russian letters from reply for {} after {} rewrites",
                            ownerId, MAX_LANGUAGE_REWRITE_ATTEMPTS);
                } else {
                    Lg2.LOGGER.warn("Ancient Ukr creditor suppressed a non-Russian reply for {} after {} rewrites",
                            ownerId, MAX_LANGUAGE_REWRITE_ATTEMPTS);
                    return false;
                }
            }
            if (pendingRequest.languageRewriteAttempts() < MAX_LANGUAGE_REWRITE_ATTEMPTS) return false;
        }
        synchronized (conversation) {
            conversation.history.add(new ChatTurn("assistant", reply));
            conversation.firstReplyPublished = true;
            trimHistory(conversation.history);
        }
        broadcastCreditorMessage(server, reply);
        return true;
    }

    private static String lastPublishedReply(CreditorConversation conversation) {
        synchronized (conversation) {
            for (int index = conversation.history.size() - 1; index >= 0; index--) {
                ChatTurn turn = conversation.history.get(index);
                if ("assistant".equals(turn.role())) return turn.content();
            }
        }
        return "";
    }

    private static String lastUserMessage(CreditorConversation conversation) {
        synchronized (conversation) {
            for (int index = conversation.history.size() - 1; index >= 0; index--) {
                ChatTurn turn = conversation.history.get(index);
                if ("user".equals(turn.role())) return turn.content();
            }
        }
        return null;
    }

    static boolean isTerminalMessage(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT).replace('\u0451', '\u0435')
                .replaceAll("[.!?,]+", " ").trim().replaceAll("\\s+", " ");
        return normalized.matches("(?:\u043d\u0430 \u044d\u0442\u043e\u043c )?\u0432\u0441\u0435(?: \u0441\u043f\u0430\u0441\u0438\u0431\u043e)?"
                + "|\u0441\u043f\u0430\u0441\u0438\u0431\u043e \u0432\u0441\u0435"
                + "|\u0431\u043e\u043b\u044c\u0448\u0435 \u043d\u0438\u0447\u0435\u0433\u043e"
                + "|\u043d\u0435\u0442 \u0441\u043f\u0430\u0441\u0438\u0431\u043e"
                + "|\u043f\u043e\u043a\u0430");
    }

    static String actionCorrection(String actionType, String userMessage, boolean activeRepayment) {
        if ("stop_repayment".equals(actionType) && !activeRepayment) {
            return "No reception is active: stop_repayment is invalid. A request to close/pay loans means "
                    + "start_repayment with selected IDs, or ask which loan using none. Existing loans can be repaid "
                    + "even when reception is inactive. Re-evaluate the latest request in conversation context.";
        }
        if ("open_credit".equals(actionType) && isTerminalMessage(userMessage)) {
            return "Do not issue another loan from an ambiguous all/done reply. Read your last question: "
                    + "all after a loan-selection question selects repayment loans; done after issuance ends the "
                    + "conversation. Earlier issuance consent is spent. If uncertain, clarify using none.";
        }
        return null;
    }

    private static boolean containsForbiddenLetters(String reply, String allowedNickname) {
        String checked = reply;
        if (allowedNickname != null && !allowedNickname.isBlank()) {
            checked = checked.replace(allowedNickname, "");
        }
        for (int offset = 0; offset < checked.length();) {
            int codePoint = checked.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) continue;
            boolean russianLetter = codePoint >= '\u0410' && codePoint <= '\u044f'
                    || codePoint == '\u0401' || codePoint == '\u0451';
            if (!russianLetter) return true;
        }
        return false;
    }

    private static String removeForbiddenLetters(String reply, String allowedNickname) {
        if (reply == null || reply.isBlank()) return "";
        StringBuilder cleaned = new StringBuilder(reply.length());
        for (int offset = 0; offset < reply.length();) {
            if (allowedNickname != null && !allowedNickname.isBlank()
                    && reply.startsWith(allowedNickname, offset)) {
                cleaned.append(allowedNickname);
                offset += allowedNickname.length();
                continue;
            }
            int codePoint = reply.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean russianLetter = codePoint >= '\u0410' && codePoint <= '\u044f'
                    || codePoint == '\u0401' || codePoint == '\u0451';
            if (!Character.isLetter(codePoint) || russianLetter) cleaned.appendCodePoint(codePoint);
        }
        return sanitizeReply(cleaned.toString().replaceAll("\\s{2,}", " "));
    }

    private static boolean containsRussianLetter(String text) {
        if (text == null) return false;
        return text.codePoints().anyMatch(codePoint -> codePoint >= '\u0410' && codePoint <= '\u044f'
                || codePoint == '\u0401' || codePoint == '\u0451');
    }
    private static String removeNickname(String reply, String nickname) {
        if (reply == null || nickname == null || nickname.isBlank()) return reply;
        String withoutNickname = reply.replaceAll(
                "(?iu)\\b" + Pattern.quote(nickname) + "\\b\\s*,?\\s*", "");
        return sanitizeReply(withoutNickname
                .replaceAll(",\\s*([.!?])", "$1")
                .replaceAll(",\\s*$", ""));
    }

    static void clearConversation(UUID ownerId) {
        if (ownerId != null) {
            CONVERSATIONS.remove(ownerId);
            AncientUkrCreditSystem.onCreditorRemoved(ownerId);
        }
    }

    private static void warnMissingApiKey(MinecraftServer server, UUID ownerId) {
        long now = System.currentTimeMillis();
        if (now >= nextMissingKeyWarningMillis) {
            nextMissingKeyWarningMillis = now + 60_000L;
            Lg2.LOGGER.warn("Ancient Ukr creditor AI is disabled: set GROQ_API_KEY or server-secrets/groq.properties");
        }
        ServerRaceSystem.finishAncientUkrCreditorConversation(server, ownerId);
    }

    private static String resolveApiKey() {
        String configured = resolveSetting("GROQ_API_KEY", "lg2.groq.apiKey", "");
        if (!configured.isBlank()) return configured.trim();
        Path secretsPath = FabricLoader.getInstance().getGameDir()
                .resolve("server-secrets").resolve("groq.properties");
        if (!Files.isRegularFile(secretsPath)) return "";
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(secretsPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties.getProperty("apiKey", "").trim();
        } catch (IOException exception) {
            Lg2.LOGGER.warn("Failed to read Groq credentials from {}", secretsPath, exception);
            return "";
        }
    }

    private static String resolveSetting(String environmentName, String propertyName, String fallback) {
        String property = System.getProperty(propertyName, "").trim();
        if (!property.isEmpty()) return property;
        String environment = System.getenv(environmentName);
        return environment == null || environment.isBlank() ? fallback : environment.trim();
    }

    private static String normalizeApiUrl(String apiUrl) {
        String normalized = apiUrl == null ? DEFAULT_API_URL : apiUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? DEFAULT_API_URL : normalized;
    }

    static String normalizeCurrency(String reply) {
        if (reply == null || reply.isEmpty()) return "";
        String amounts = AMOUNT_WITH_CURRENCY.matcher(reply.replace('\ue981', BITCOIN_SIGN)).replaceAll(match ->
                Matcher.quoteReplacement(match.group(1) + BITCOIN_SIGN));
        return RUBLES.matcher(amounts).replaceAll(match -> {
            String word = match.group().toLowerCase(Locale.ROOT).replace('\u0451', '\u0435');
            String replacement = switch (word) {
                case "\u0440\u0443\u0431\u043b\u0435\u0439" -> "\u0431\u0438\u0442\u043a\u043e\u0438\u043d\u043e\u0432";
                case "\u0440\u0443\u0431\u043b\u044f", "\u0440\u0443\u0431\u043b\u044e" -> "\u0431\u0438\u0442\u043a\u043e\u0438\u043d\u0430";
                case "\u0440\u0443\u0431\u043b\u044f\u043c\u0438" -> "\u0431\u0438\u0442\u043a\u043e\u0438\u043d\u0430\u043c\u0438";
                case "\u0440\u0443\u0431\u043b\u044f\u0445" -> "\u0431\u0438\u0442\u043a\u043e\u0438\u043d\u0430\u0445";
                default -> "\u0431\u0438\u0442\u043a\u043e\u0438\u043d\u044b";
            };
            return Matcher.quoteReplacement(replacement);
        });
    }

    private static String sanitizeReply(String reply) {
        String sanitized = reply == null ? "" : reply
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.length() > MAX_REPLY_CHARACTERS) {
            sanitized = sanitized.substring(0, MAX_REPLY_CHARACTERS - 1).trim() + "\u2026";
        }
        return sanitized;
    }

    private static void trimHistory(List<ChatTurn> history) {
        while (history.size() > MAX_HISTORY_MESSAGES) history.remove(0);
        int characters = history.stream().mapToInt(turn -> turn.content().length()).sum();
        while (characters > MAX_HISTORY_CHARACTERS && history.size() > 1) {
            characters -= history.remove(0).content().length();
        }
    }

    private static String conciseFailure(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    record AiReply(String reply, String actionType, Integer creditNumber,
                           List<Integer> creditNumbers, Integer amount) {
    }

    private record ChatTurn(String role, String content) {
    }

    private record PendingRequest(String userMessage, String authoritativeEvent, boolean closeAfterReply,
                                  int languageRewriteAttempts, int transportRetryCount) {
        private static PendingRequest user(String message) {
            return new PendingRequest(message, null, false, 0, 0);
        }

        private static PendingRequest event(String event, boolean closeAfterReply) {
            return new PendingRequest(null, event, closeAfterReply, 0, 0);
        }

        private static PendingRequest rewrite(String event, boolean closeAfterReply, int attempts) {
            return new PendingRequest(null, event, closeAfterReply, attempts, 0);
        }

        private static PendingRequest recheckAction() {
            return new PendingRequest(null, null, false, 0, 0);
        }

        private PendingRequest retryTransport() {
            return new PendingRequest(userMessage, authoritativeEvent, closeAfterReply,
                    languageRewriteAttempts, transportRetryCount + 1);
        }
    }

    private static final class CreditorConversation {
        private final UUID creditorId;
        private final Deque<PendingRequest> pendingRequests = new ArrayDeque<>();
        private final List<ChatTurn> history = new ArrayList<>();
        private boolean requestInFlight;
        private boolean firstReplyPublished;


        private CreditorConversation(UUID creditorId) {
            this.creditorId = creditorId;
        }
    }
}
