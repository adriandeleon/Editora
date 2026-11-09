package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import com.editora.config.PathKeys;
import org.jspecify.annotations.Nullable;

/**
 * The Map's one Back/Forward history (Map code browser gap 3). Before it the Map kept two: its places — the rows
 * selected, the files opened, the search's landings — walked by Alt+←/→ and the ‹ › buttons, and the cards the code
 * was followed through, walked by Back typed in a card. Each Back only knew its own half, so after following the code
 * from a card, Back on the map skipped the cards and Back in a card skipped the rows.
 *
 * <p>Now every stop is a place on the Map (a row's path) and, when the code was followed through a card, that card and
 * its caret then. Alt+←/→, the ‹ › buttons and their menu, and Back and Forward typed in a card or on the map all walk
 * this one list. A stop on a card brings the card back with its caret where it was; once the card is closed the stop
 * is its place, its row revealed, as any other.
 *
 * <p>How places are recorded is as before (Project Map review 3 B5, {@link Head}): a place is never replaced, a run
 * of keyboard steps is one stop (the row it leads to), the landings a typed query makes are one stop (its last). At
 * most {@link #LIMIT} stops, the oldest dropped first.
 *
 * @param <C> a card; the Map's are {@link ProjectMapPreview}s (generic so this is tested without JavaFX)
 */
final class MapHistory<C> {

    /** How many stops the history keeps. */
    static final int LIMIT = 100;

    /** How the newest stop got there: a place, or where a run of steps or of search landings has got to. */
    enum Head {
        /** A place: never replaced; the next stop goes after it. */
        PLACE,
        /** Where a run of keyboard steps has got to: the next step replaces it. */
        STEP,
        /** Where the search landed by itself: the next such landing replaces it. */
        LANDING
    }

    /**
     * A stop: a place on the Map — a row's path, a declaration's made-up path for a declaration card — and, when the
     * code was followed through a card, that card and its caret's offset then ({@code -1}: leave the caret). Once that
     * card is closed, {@code line} is the file's 0-based line its caret was on, so Back opens the card there again
     * (Map code browser gap 6); {@code -1} for a row's place.
     */
    record Stop<C>(Path place, @Nullable C card, int caret, int line) {
        Stop(Path place, @Nullable C card, int caret) {
            this(place, card, caret, -1);
        }
    }

    private final List<Stop<C>> stops = new ArrayList<>();
    private final List<Path> places = new java.util.AbstractList<>() {
        @Override
        public Path get(int at) {
            return stops.get(at).place();
        }

        @Override
        public int size() {
            return stops.size();
        }
    };
    private final Predicate<C> open;
    private final ToIntFunction<C> caretOf;
    private final java.util.function.ToIntBiFunction<C, Integer> lineOf;
    private int index = -1;
    private Head head = Head.PLACE;

    /**
     * @param open whether a card is still open (a closed card's stop is its place)
     * @param caretOf a card's caret offset now (kept on its stop when the history moves on from it)
     */
    MapHistory(Predicate<C> open, ToIntFunction<C> caretOf) {
        this(open, caretOf, (card, caret) -> -1);
    }

    /** @param lineOf the file's 0-based line of a card's caret offset (kept on its stops when the card closes) */
    MapHistory(Predicate<C> open, ToIntFunction<C> caretOf, java.util.function.ToIntBiFunction<C, Integer> lineOf) {
        this.open = open;
        this.caretOf = caretOf;
        this.lineOf = lineOf;
    }

    // --- recording --------------------------------------------------------------------------------------------

    /**
     * A place was selected, as a place or as the head of a run (see {@link Head}): a step replaces a step head and a
     * landing a landing head, so Back does not retrace every keystroke; a run that comes back to the stop before it
     * folds away. The place the history is at already adds nothing. Whether the history changed.
     */
    boolean record(Path place, Head kind) {
        if (index >= 0 && PathKeys.samePath(stops.get(index).place(), place)) {
            if (kind == Head.PLACE && index == stops.size() - 1) {
                head = Head.PLACE; // the run's row became a place: the next step goes after it
            }
            return false;
        }
        boolean atHead = index == stops.size() - 1;
        if (kind != Head.PLACE
                && atHead
                && head == kind
                && index >= 0
                && stops.get(index).card() == null) {
            stops.set(index, new Stop<>(place, null, -1));
            if (index > 0 && PathKeys.samePath(stops.get(index - 1).place(), place)) {
                stops.remove(index--); // the run came back to where it started
                head = Head.PLACE;
            }
            return true;
        }
        add(new Stop<>(place, null, -1));
        head = kind;
        return true;
    }

    /**
     * The code was followed from card {@code from} (its caret {@code fromCaret} before the step) to card {@code to}
     * — another card, or a place further along {@code from} itself. Both are stops: Back from {@code to} returns to
     * {@code from} with its caret where it was. A new step forgets the stops Forward would go to.
     */
    void followed(C from, Path fromPlace, int fromCaret, C to, Path toPlace) {
        settle(from, fromPlace, fromCaret);
        add(new Stop<>(toPlace, to, -1));
        head = Head.PLACE;
    }

    /**
     * {@code card}, its caret at {@code caret}, is where the history is: the current stop when it is that card's (its
     * caret kept) or the card's place without a card (the card added to it); otherwise a new stop after it.
     */
    private void settle(C card, Path place, int caret) {
        Stop<C> current = current();
        if (current != null
                && (current.card() == card || current.card() == null && samePlace(current.place(), place))) {
            stops.set(index, new Stop<>(place, card, caret));
            return;
        }
        add(new Stop<>(place, card, caret));
        head = Head.PLACE;
    }

    /**
     * Whether a stop's {@code place} is where {@code cardPlace} — a card's — is: the same path, or the file of the
     * declaration the card shows (the file's row was selected and the card opened from it).
     */
    static boolean samePlace(Path place, Path cardPlace) {
        if (PathKeys.samePath(place, cardPlace)) {
            return true;
        }
        Path file = cardPlace;
        while (ProjectMapStructure.isSymbolPath(file) && file.getParent() != null) {
            file = file.getParent();
        }
        return file != cardPlace && PathKeys.samePath(place, file);
    }

    private void add(Stop<C> stop) {
        if (index < stops.size() - 1) {
            stops.subList(index + 1, stops.size()).clear(); // a new stop forgets the Forward ones
        }
        stops.add(stop);
        if (stops.size() > LIMIT) {
            stops.removeFirst();
        }
        index = stops.size() - 1;
    }

    // --- moving -----------------------------------------------------------------------------------------------

    /**
     * Back ({@code delta} -1) or Forward (+1), or further: the stop to go to, now the current one, or null when there
     * is none. {@code typedIn}, when the keys were typed in a card, is where the history is: its caret is kept on its
     * stop, and Back from a card the history has no stop for yet adds one, so Forward returns to it.
     */
    @Nullable
    Stop<C> step(int delta, @Nullable C typedIn, @Nullable Path typedPlace) {
        keepCaret();
        if (typedIn != null && typedPlace != null && open.test(typedIn)) {
            Stop<C> current = current();
            if (current == null || current.card() != typedIn) {
                if (delta >= 0) {
                    return go(index + delta); // Forward from a card not in the history: where the history goes on
                }
                settle(typedIn, typedPlace, caretOf.applyAsInt(typedIn));
            }
        }
        return go(index + delta);
    }

    /** The stop {@code target} (counted from the oldest), as that many Back or Forward presses would; null when none. */
    @Nullable
    Stop<C> jump(int target) {
        keepCaret();
        return go(target);
    }

    private @Nullable Stop<C> go(int target) {
        if (target < 0 || target >= stops.size() || target == index) {
            return null;
        }
        Stop<C> stop = stops.get(target);
        C card = stop.card();
        if (card != null && !open.test(card)) {
            stop = new Stop<>(stop.place(), null, -1, stop.line()); // closed since: its place
            stops.set(target, stop);
        }
        index = target;
        head = Head.PLACE;
        return stop;
    }

    /** Back or Forward opened {@code card} again for the current stop, whose card was closed: the stop is on it again. */
    void reopened(C card) {
        Stop<C> current = current();
        if (current != null && current.card() == null) {
            stops.set(index, new Stop<>(current.place(), card, -1));
        }
    }

    /** The current stop's card, when it is open: its caret now is where Back or Forward returns to. */
    private void keepCaret() {
        Stop<C> current = current();
        C card = current == null ? null : current.card();
        if (current != null && card != null && open.test(card)) {
            stops.set(index, new Stop<>(current.place(), card, caretOf.applyAsInt(card)));
        }
    }

    // --- upkeep -----------------------------------------------------------------------------------------------

    /**
     * {@code card} closed: its stops become their places, each with the line its caret was on — Back opens the card
     * there again — and the same place at the same line twice in a row is kept once.
     */
    void cardClosed(C card) {
        boolean changed = false;
        int now = caretOf.applyAsInt(card);
        for (int at = 0; at < stops.size(); at++) {
            Stop<C> stop = stops.get(at);
            if (stop.card() == card) {
                int caret = stop.caret() >= 0 ? stop.caret() : now;
                stops.set(at, new Stop<>(stop.place(), null, -1, Math.max(-1, lineOf.applyAsInt(card, caret))));
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        for (int at = stops.size() - 1; at > 0; at--) {
            Stop<C> stop = stops.get(at);
            Stop<C> before = stops.get(at - 1);
            if (stop.card() == null
                    && before.card() == null
                    && stop.line() == before.line()
                    && PathKeys.samePath(stop.place(), before.place())) {
                stops.remove(at);
                if (index >= at) {
                    index--;
                }
            }
        }
    }

    /** Removes every stop at {@code dead} (a path that no longer resolves), the index kept on {@code current}. */
    void drop(Path dead, @Nullable Path current) {
        boolean removed = stops.removeIf(stop -> PathKeys.samePath(stop.place(), dead));
        if (!removed) {
            return;
        }
        int nearest = -1;
        for (int at = 0; at < stops.size(); at++) {
            if (PathKeys.samePath(stops.get(at).place(), current)
                    && (nearest < 0 || Math.abs(at - index) < Math.abs(nearest - index))) {
                nearest = at;
            }
        }
        index = nearest >= 0 ? nearest : Math.min(index, stops.size() - 1);
    }

    /** Forgets every stop (another root). */
    void clear() {
        stops.clear();
        index = -1;
        head = Head.PLACE;
    }

    /** The last session's places, the current one at {@code at}: the newest {@link #LIMIT} kept. */
    void restore(List<Path> places, int at) {
        if (places.isEmpty()) {
            return;
        }
        int from = Math.max(0, places.size() - LIMIT);
        stops.clear();
        places.subList(from, places.size()).forEach(place -> stops.add(new Stop<>(place, null, -1)));
        index = Math.clamp(at - from, 0, stops.size() - 1);
        head = Head.PLACE;
    }

    // --- reading ----------------------------------------------------------------------------------------------

    /** Every stop, the oldest first. */
    List<Stop<C>> stops() {
        return Collections.unmodifiableList(stops);
    }

    /** The places of every stop, the oldest first, as the history changes (a workspace keeps these: not the cards). */
    List<Path> places() {
        return places;
    }

    /** The current stop's position in {@link #stops()}, or -1 with none. */
    int index() {
        return index;
    }

    boolean canGoBack() {
        return index > 0;
    }

    boolean canGoForward() {
        return index >= 0 && index < stops.size() - 1;
    }

    private @Nullable Stop<C> current() {
        return index >= 0 && index < stops.size() ? stops.get(index) : null;
    }

    /** The cards Back would go to, the newest first (the stops before the current one that are on a card). For tests. */
    List<C> cardsBack() {
        List<C> back = new ArrayList<>();
        for (int at = index - 1; at >= 0; at--) {
            C card = stops.get(at).card();
            if (card != null) {
                back.add(card);
            }
        }
        return back;
    }
}
