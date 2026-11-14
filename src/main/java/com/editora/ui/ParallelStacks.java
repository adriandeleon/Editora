package com.editora.ui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Pure: Parallel Stacks (Visual Studio's Parallel Stacks window) — every thread's call stack at one moment drawn as one
 * tree. Threads that went through the same calls share a box; where their stacks part, the box branches, a box per way
 * they went. The outermost calls are at the bottom, as a stack grows up: a pool's twenty workers that all wait in one
 * place are one box of twenty threads above the box of the code that started them.
 *
 * <p>Frames are the same when they are the same function in the same file, whatever line each thread is on: a box
 * frame on several lines says so. A box ends where a thread's stack ends or where the threads in it go different ways.
 * Built from a {@link ThreadDump}; threads with no stack (running ones) are not in it.
 */
final class ParallelStacks {

    /**
     * One box: the frames its threads have in common, outermost first, and the boxes above it, one per way they went
     * on. {@code threads} are those whose stacks pass through it, in dump order.
     */
    record Box(List<Frame> frames, List<DapModels.ThreadStack> threads, List<Box> above) {

        /** The innermost frame, where the threads went on (or stopped). */
        Frame top() {
            return frames.getLast();
        }

        /** Whether the thread the Debug window shows passes through this box. */
        boolean hasShownThread() {
            return threads.stream().anyMatch(t -> t.thread().shown());
        }
    }

    /**
     * One frame of a box: the frame of the first thread, and whether the threads are on other lines of it ({@code
     * lines} &gt; 1). {@code shownThreadsFrame} is the shown thread's own frame here (its line), else null.
     */
    record Frame(DapModels.StackFrameInfo frame, int lines, DapModels.@Nullable StackFrameInfo shownThreadsFrame) {

        /** The frame as a box row says it: the function and its line, or "several lines". */
        String text() {
            String name = DebugPanelText.frameName(frame.name());
            if (lines > 1) {
                return tr("parallelstacks.severalLines", name);
            }
            return frame.line() >= 0 ? name + ":" + (frame.line() + 1) : name; // lines are 0-based
        }

        /** The frame a click opens: the shown thread's, else the first thread's. */
        DapModels.StackFrameInfo opened() {
            DapModels.StackFrameInfo own = shownThreadsFrame;
            return own != null ? own : frame;
        }
    }

    private final ThreadDump dump;
    private final List<Box> roots;

    ParallelStacks(ThreadDump dump) {
        this.dump = dump;
        this.roots = build(dump.threads());
    }

    ThreadDump dump() {
        return dump;
    }

    /** The bottom boxes: one per outermost frame the threads start from (Go's goroutines may start from several). */
    List<Box> roots() {
        return roots;
    }

    /** How many threads had no stack to draw (running, or not walked). */
    long withoutStack() {
        return dump.threads().stream().filter(t -> t.frames().isEmpty()).count();
    }

    // --- building ----------------------------------------------------------------------------------------------

    /** One call of the merged trie: a frame shared by {@code threads}, the frame each of them has there. */
    private static final class Node {
        final List<DapModels.ThreadStack> threads = new ArrayList<>();
        final List<DapModels.StackFrameInfo> frames = new ArrayList<>();
        final Map<String, Node> children = new LinkedHashMap<>();
    }

    /** The key of a frame: its function and its file, not its line. */
    static String key(DapModels.StackFrameInfo f) {
        return f.name() + "\u0000" + (f.file() != null ? f.file().toString() : Objects.toString(f.sourceUri(), ""));
    }

    private static List<Box> build(List<DapModels.ThreadStack> threads) {
        Node root = new Node();
        for (DapModels.ThreadStack t : threads) {
            List<DapModels.StackFrameInfo> frames = t.frames();
            Node at = root;
            for (int i = frames.size() - 1; i >= 0; i--) { // outermost first
                DapModels.StackFrameInfo f = frames.get(i);
                at = at.children.computeIfAbsent(key(f), k -> new Node());
                at.threads.add(t);
                at.frames.add(f);
            }
        }
        List<Box> boxes = new ArrayList<>();
        for (Node n : root.children.values()) {
            boxes.add(box(n));
        }
        return List.copyOf(boxes);
    }

    /** The box starting at {@code start}: it goes on up while one way leads on and every thread takes it. */
    private static Box box(Node start) {
        List<Frame> frames = new ArrayList<>();
        Node at = start;
        while (true) {
            frames.add(frame(at));
            if (at.children.size() != 1) {
                break;
            }
            Node next = at.children.values().iterator().next();
            if (next.threads.size() != at.threads.size()) {
                break; // some threads' stacks end here
            }
            at = next;
        }
        List<Box> above = new ArrayList<>();
        for (Node n : at.children.values()) {
            above.add(box(n));
        }
        return new Box(List.copyOf(frames), List.copyOf(start.threads), List.copyOf(above));
    }

    private static Frame frame(Node n) {
        long lines = n.frames.stream()
                .mapToInt(DapModels.StackFrameInfo::line)
                .distinct()
                .count();
        DapModels.StackFrameInfo shown = null;
        for (int i = 0; i < n.threads.size(); i++) {
            if (n.threads.get(i).thread().shown()) {
                shown = n.frames.get(i);
            }
        }
        return new Frame(n.frames.getFirst(), (int) lines, shown);
    }

    // --- text ---------------------------------------------------------------------------------------------------

    /**
     * The tree as text: each box with its threads and its frames, innermost first as a stack trace is, the boxes
     * above it indented under it.
     */
    String text() {
        StringBuilder out = new StringBuilder(dump.title()).append('\n');
        for (Box b : roots) {
            text(out, b, 0);
        }
        return out.toString();
    }

    private static void text(StringBuilder out, Box b, int depth) {
        String indent = "    ".repeat(depth);
        out.append('\n').append(indent).append(heading(b)).append('\n');
        for (int i = b.frames().size() - 1; i >= 0; i--) {
            out.append(indent).append("    ").append(b.frames().get(i).text()).append('\n');
        }
        for (Box above : b.above()) {
            text(out, above, depth + 1);
        }
    }

    /** A box's heading: its one thread's name, else how many threads and the first one's. */
    static String heading(Box b) {
        if (b.threads().size() == 1) {
            return ThreadDump.name(b.threads().getFirst());
        }
        return tr(
                "parallelstacks.threads",
                b.threads().size(),
                ThreadDump.name(b.threads().getFirst()));
    }

    // --- layout -------------------------------------------------------------------------------------------------

    /** Where a box is drawn: its left, its top, its width and its height. */
    record Place(double x, double y, double width, double height) {
        double centerX() {
            return x + width / 2;
        }

        double bottom() {
            return y + height;
        }
    }

    /**
     * Lays the tree out: each box {@code width} wide and as tall as its rows ({@code header} plus {@code row} a
     * frame), the boxes above a box side by side {@code gap} apart and centred over it, each one {@code gap} above it;
     * the roots side by side. The tallest pile ends at {@code margin} from the top. Pure.
     */
    Map<Box, Place> layout(double width, double header, double row, double gap, double margin) {
        Map<Box, Double> span = new IdentityHashMap<>();
        for (Box b : roots) {
            span(b, width, gap, span);
        }
        Map<Box, Place> places = new IdentityHashMap<>();
        double x = margin;
        for (Box b : roots) {
            place(b, x, 0, width, header, row, gap, span, places);
            x += spanOf(span, b) + gap;
        }
        double top = places.values().stream().mapToDouble(Place::y).min().orElse(0);
        Map<Box, Place> shifted = new IdentityHashMap<>();
        places.forEach((b, p) -> shifted.put(b, new Place(p.x(), p.y() - top + margin, p.width(), p.height())));
        return shifted;
    }

    /** How wide {@code b} and everything above it are. */
    private static double span(Box b, double width, double gap, Map<Box, Double> span) {
        double above = 0;
        for (Box a : b.above()) {
            above += span(a, width, gap, span);
        }
        above += gap * Math.max(0, b.above().size() - 1);
        double s = Math.max(width, above);
        span.put(b, s);
        return s;
    }

    /** Places {@code b} with its bottom at {@code bottom} (0 for a root, going up negative) in its span from {@code left}. */
    private static void place(
            Box b,
            double left,
            double bottom,
            double width,
            double header,
            double row,
            double gap,
            Map<Box, Double> span,
            Map<Box, Place> places) {
        double height = header + row * b.frames().size();
        double s = spanOf(span, b);
        Place p = new Place(left + (s - width) / 2, bottom - height, width, height);
        places.put(b, p);
        double above = 0;
        for (Box a : b.above()) {
            above += spanOf(span, a);
        }
        above += gap * Math.max(0, b.above().size() - 1);
        double x = left + (s - above) / 2;
        for (Box a : b.above()) {
            place(a, x, p.y() - gap, width, header, row, gap, span, places);
            x += spanOf(span, a) + gap;
        }
    }

    private static double spanOf(Map<Box, Double> span, Box b) {
        Double s = span.get(b);
        return s == null ? 0 : s;
    }

    /** Every box, the roots first and each box before those above it. */
    List<Box> boxes() {
        List<Box> all = new ArrayList<>();
        for (Box b : roots) {
            collect(b, all);
        }
        return all;
    }

    private static void collect(Box b, List<Box> all) {
        all.add(b);
        for (Box a : b.above()) {
            collect(a, all);
        }
    }
}
