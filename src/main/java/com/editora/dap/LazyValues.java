package com.editora.dap;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the variables an adapter sends as {@code presentationHint.lazy} (Java debugging review 2, B101), and
 * remembers what one stop has already fetched (review 3, B201/B202/B207).
 *
 * <p>java-debug 0.53.2 marks every object whose class has its own {@code toString()} — {@code Integer} and the other
 * boxes, enums, records, dates, most domain classes — as lazy: the row says only {@code Integer@62}, and its single
 * child holds the real value ({@code Integer@62 "0"}) with the object's fields below it. Per DAP the client asks for
 * that child and shows it in place of the row; this does so at once (IntelliJ shows the value without a click).
 *
 * <p>The child runs the program's {@code toString()} on the stopped thread, and java-debug answers one request at a
 * time, with no limit: a slow {@code toString()} held up every stop, and one that never returned wedged the session.
 * So a stop has a <b>budget</b> ({@link #BUDGET_MILLIS} of time with a {@code toString()} outstanding, in total);
 * rows still unanswered when it runs out come back marked {@link DapModels.ValueNote#NOT_LOADED}, and a click loads
 * them ({@link #resolveNow}). Each object is asked once per stop, whoever asks — the Variables tree and the inline
 * values share one answer, keyed by the object's {@code Type@id} (java-debug hands out a new
 * {@code variablesReference} with every reply, so a cache keyed by reference never hit).
 */
final class LazyValues {

    /** The time a stop may spend waiting for {@code toString()} answers before rows wait for a click. */
    static final long BUDGET_MILLIS = 1_500;

    /** How many children are asked for at once: enough to hide the round trips, few enough to stop at the budget. */
    private static final int LANES = 4;

    private final LongSupplier clock;

    LazyValues() {
        this(System::nanoTime);
    }

    /** {@code clock} in nanoseconds (a test moves it by hand). */
    LazyValues(LongSupplier clock) {
        this.clock = clock;
    }

    /** Resolved rows by object ({@link #key}); valid while the program stays paused. */
    private final Map<String, CompletableFuture<DapModels.VariableInfo>> cache = new ConcurrentHashMap<>();

    /** The raw {@code variables} replies of this stop, by request: the tree and the inline values share one. */
    private final Map<String, CompletableFuture<List<DapModels.VariableInfo>>> replies = new ConcurrentHashMap<>();

    /** Children asked for and not answered, with when (nanos) — kept across stops: the adapter is still busy. */
    private final Map<Integer, Asked> unanswered = new ConcurrentHashMap<>();

    private record Asked(long at, String what) {}

    private final AtomicInteger asks = new AtomicInteger();

    // The budget: time during which at least one toString() was outstanding, this stop (guarded by this).
    private int inFlight;
    private long busySince;
    private long spent;

    /** Whether the current stop came from Pause, where java-debug cannot run {@code toString()}. */
    private volatile boolean pausedStop;

    /** A new stop: a fresh budget, nothing remembered. {@code paused}: the program was stopped by Pause. */
    void newStop(boolean paused) {
        pausedStop = paused;
        clear();
    }

    /**
     * Forgets every resolved row and reply and refills the budget: the program ran, or something (a console
     * evaluation, Set Value) may have changed what the objects say.
     */
    void clear() {
        cache.clear();
        replies.clear();
        synchronized (this) {
            spent = 0;
            busySince = clock.getAsLong();
        }
    }

    /** The session ended: also forgets the requests the adapter never answered. */
    void reset() {
        clear();
        unanswered.clear();
        pausedStop = false;
    }

    /**
     * The one reply to a {@code variables} request this stop, sent by {@code send} only the first time: {@code key}
     * names the request (reference, filter, page). A failed reply is not kept.
     */
    CompletableFuture<List<DapModels.VariableInfo>> reply(
            String key, Supplier<CompletableFuture<List<DapModels.VariableInfo>>> send) {
        CompletableFuture<List<DapModels.VariableInfo>> known = replies.get(key);
        if (known != null && !known.isCompletedExceptionally()) {
            return known;
        }
        CompletableFuture<List<DapModels.VariableInfo>> sent = send.get();
        replies.put(key, sent);
        return sent;
    }

    /**
     * What a {@code toString()} still running in the program shows as ({@code Type@id}), when one was asked more
     * than the budget ago and is not answered — the adapter takes no other request until it returns. Else null.
     */
    @Nullable
    String stuck() {
        long now = clock.getAsLong();
        long limit = TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS);
        for (Asked a : unanswered.values()) {
            if (now - a.at() > limit) {
                return a.what();
            }
        }
        return null;
    }

    /**
     * {@code vars} with every lazy row replaced by its resolved form, within the stop's budget. {@code children}
     * fetches one reference's children; a fetch that fails or does not give exactly one child leaves the row as it
     * came, and a row not answered in time comes back {@link DapModels.ValueNote#NOT_LOADED}.
     */
    CompletableFuture<List<DapModels.VariableInfo>> resolveAll(
            List<DapModels.VariableInfo> vars, IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children) {
        if (vars == null || vars.stream().noneMatch(LazyValues::needsResolving)) {
            return CompletableFuture.completedFuture(vars);
        }
        DapModels.VariableInfo[] out = vars.toArray(new DapModels.VariableInfo[0]);
        int[] todo = IntStream.range(0, out.length)
                .filter(i -> needsResolving(out[i]))
                .toArray();
        AtomicInteger next = new AtomicInteger();
        CompletableFuture<?>[] lanes = new CompletableFuture<?>[Math.min(LANES, todo.length)];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = lane(out, todo, next, children);
        }
        return CompletableFuture.allOf(lanes).thenApply(done -> List.of(out));
    }

    /** Resolves rows one after another until none is left; rows answered at once do not deepen the stack. */
    private CompletableFuture<Void> lane(
            DapModels.VariableInfo[] out,
            int[] todo,
            AtomicInteger next,
            IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children) {
        while (true) {
            int k = next.getAndIncrement();
            if (k >= todo.length) {
                return CompletableFuture.completedFuture(null);
            }
            int i = todo[k];
            CompletableFuture<DapModels.VariableInfo> row = resolve(out[i], children, true);
            if (row.isDone()) {
                out[i] = row.join();
                continue;
            }
            return row.thenCompose(r -> {
                out[i] = r;
                return lane(out, todo, next, children);
            });
        }
    }

    /** One row, resolved now whatever the budget says (a click on a row that was not loaded). */
    CompletableFuture<DapModels.VariableInfo> resolveNow(
            DapModels.VariableInfo v, IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children) {
        return resolve(v, children, false);
    }

    private CompletableFuture<DapModels.VariableInfo> resolve(
            DapModels.VariableInfo v,
            IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children,
            boolean budgeted) {
        if (!needsResolving(v)) {
            return CompletableFuture.completedFuture(v);
        }
        String key = key(v);
        CompletableFuture<DapModels.VariableInfo> known = cache.get(key);
        if (known == null) {
            if (budgeted && remainingNanos() <= 0) {
                return CompletableFuture.completedFuture(notLoaded(v));
            }
            known = ask(v, key, children);
        }
        CompletableFuture<DapModels.VariableInfo> named = known.thenApply(r -> rename(r, v));
        if (!budgeted || named.isDone()) {
            return named;
        }
        long left = remainingNanos();
        if (left <= 0) {
            return CompletableFuture.completedFuture(notLoaded(v));
        }
        return named.completeOnTimeout(notLoaded(v), left, TimeUnit.NANOSECONDS);
    }

    /** Sends the one request for {@code v}'s child and remembers its answer for the rest of the stop. */
    private CompletableFuture<DapModels.VariableInfo> ask(
            DapModels.VariableInfo v,
            String key,
            IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children) {
        int id = asks.incrementAndGet();
        began();
        unanswered.put(id, new Asked(clock.getAsLong(), v.value()));
        CompletableFuture<List<DapModels.VariableInfo>> asked;
        try {
            asked = children.apply(v.variablesReference());
        } catch (RuntimeException e) {
            asked = CompletableFuture.failedFuture(e);
        }
        boolean paused = pausedStop;
        CompletableFuture<DapModels.VariableInfo> answer = new CompletableFuture<>();
        cache.put(key, answer);
        asked.whenComplete((kids, e) -> {
            ended();
            if (!timedOut(e)) {
                unanswered.remove(id); // a timeout is Editora giving up: the adapter is still busy with it
            }
            DapModels.VariableInfo r =
                    e != null || kids == null || kids.size() != 1 ? v : resolved(v, kids.get(0), paused);
            if (r == v || r.note() != null) {
                cache.remove(key, answer); // a failure is not remembered: asking again may work
            }
            answer.complete(r);
        });
        return answer;
    }

    private static boolean timedOut(@Nullable Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private synchronized void began() {
        if (inFlight++ == 0) {
            busySince = clock.getAsLong();
        }
    }

    private synchronized void ended() {
        if (inFlight > 0 && --inFlight == 0) {
            spent += clock.getAsLong() - busySince;
        }
    }

    /** What is left of this stop's budget, in nanoseconds (≤ 0: spent). */
    synchronized long remainingNanos() {
        long used = spent + (inFlight > 0 ? clock.getAsLong() - busySince : 0);
        return TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS) - used;
    }

    private static boolean needsResolving(DapModels.VariableInfo v) {
        return v != null && v.lazy() && v.variablesReference() > 0;
    }

    /** java-debug's text for an object: {@code Type@id}, the same for the same object during a stop. */
    private static final Pattern OBJECT = Pattern.compile("^\\S+@\\d+$");

    /** Pure: the cache key of a lazy row — the object it shows ({@code Type@id}), else its reference. */
    static String key(DapModels.VariableInfo v) {
        String value = v.value();
        return value != null && OBJECT.matcher(value).matches() ? value : "#" + v.variablesReference();
    }

    /** Pure: {@code v} shown as it came, waiting for a click ({@link DapModels.ValueNote#NOT_LOADED}). */
    static DapModels.VariableInfo notLoaded(DapModels.VariableInfo v) {
        return new DapModels.VariableInfo(
                v.name(),
                v.value(),
                v.type(),
                v.variablesReference(),
                v.namedVariables(),
                v.indexedVariables(),
                true,
                DapModels.ValueNote.NOT_LOADED);
    }

    /** {@code r} under the name of the row that asked ({@code asker}): one object can be several variables. */
    private static DapModels.VariableInfo rename(DapModels.VariableInfo r, DapModels.VariableInfo asker) {
        if (r.name().equals(asker.name())) {
            return r;
        }
        if (r.lazy() && r.note() == null) {
            return asker; // not resolved: the asker's own row, its own reference included
        }
        return new DapModels.VariableInfo(
                asker.name(),
                r.value(),
                r.type(),
                r.variablesReference(),
                r.namedVariables(),
                r.indexedVariables(),
                r.lazy(),
                r.note());
    }

    /** {@link #resolved(DapModels.VariableInfo, DapModels.VariableInfo, boolean)} after a breakpoint or step. */
    static DapModels.VariableInfo resolved(DapModels.VariableInfo lazy, DapModels.VariableInfo child) {
        return resolved(lazy, child, false);
    }

    /**
     * Pure: the row a lazy variable becomes once its one {@code child} is known — the child's value and children
     * under the variable's own name and type. A boxed primitive shows its bare value ({@code 42}, not
     * {@code Integer@13 "42"}), as IntelliJ does.
     *
     * <p>When java-debug could not run {@code toString()} — the thread was stopped by Pause, or the method threw —
     * the child reads {@code Integer@13 null}, and that "null" read as the value (B202). The row then keeps its
     * {@code Type@id} with a note saying why ({@code paused}: the stop came from Pause), and its fields below.
     */
    static DapModels.VariableInfo resolved(DapModels.VariableInfo lazy, DapModels.VariableInfo child, boolean paused) {
        String value = child.value() == null || child.value().isEmpty() ? lazy.value() : child.value();
        String type = lazy.type() == null || lazy.type().isEmpty() ? child.type() : lazy.type();
        if (noToString(lazy.value(), value)) {
            return new DapModels.VariableInfo(
                    lazy.name(),
                    lazy.value(),
                    type,
                    child.variablesReference(),
                    child.namedVariables(),
                    child.indexedVariables(),
                    false,
                    paused ? DapModels.ValueNote.NO_TO_STRING_AFTER_PAUSE : DapModels.ValueNote.TO_STRING_FAILED);
        }
        String bare = boxedValue(type, value);
        // A boxed value's only children are its own `value` field: nothing worth a level of its own.
        boolean leaf = bare != null;
        return new DapModels.VariableInfo(
                lazy.name(),
                bare != null ? bare : value,
                type,
                leaf ? 0 : child.variablesReference(),
                leaf ? 0 : child.namedVariables(),
                leaf ? 0 : child.indexedVariables());
    }

    /** Pure: whether a lazy child's value is java-debug's "no toString()": the row's own text plus " null". */
    static boolean noToString(@Nullable String lazyValue, @Nullable String childValue) {
        return lazyValue != null && !lazyValue.isEmpty() && (lazyValue + " null").equals(childValue);
    }

    /** The boxed types whose {@code toString()} is the whole value. */
    private static final Set<String> BOXES =
            Set.of("Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character");

    /** java-debug's rendering of an object with its {@code toString()}: {@code Type@id "text"}. */
    private static final Pattern DETAILED = Pattern.compile("^\\S+@\\d+ \"(.*)\"$", Pattern.DOTALL);

    /**
     * Pure: the bare value of a boxed primitive rendered as {@code Integer@13 "42"} — {@code 42}, or {@code 'c'} for
     * a {@code Character} — else null.
     */
    static @Nullable String boxedValue(@Nullable String type, @Nullable String value) {
        if (type == null || value == null) {
            return null;
        }
        String simple = type.startsWith("java.lang.") ? type.substring("java.lang.".length()) : type;
        if (!BOXES.contains(simple)) {
            return null;
        }
        Matcher m = DETAILED.matcher(value);
        if (!m.matches()) {
            return null;
        }
        return "Character".equals(simple) ? "'" + m.group(1) + "'" : m.group(1);
    }
}
