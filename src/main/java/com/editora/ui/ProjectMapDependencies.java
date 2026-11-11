package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The project's dependencies between its files, as the Map's Dependencies card shows them (Map code browser gap 14):
 * around a file or a folder — what uses it and what it uses, file by file or folder by folder — and inside a folder —
 * how its parts use each other, as a matrix whose parts are in an order where each uses only those after it, but for
 * the parts that use each other in a cycle. Built from {@link ProjectMapImports}' edges. Pure and immutable.
 */
final class ProjectMapDependencies {

    /** The most parts a matrix shows; the parts with the fewest dependencies are left out past it. */
    static final int MAX_PARTS = 40;

    /** How the files around a subject are shown: each file, or each folder holding them. */
    enum Grouping {
        FILES,
        FOLDERS
    }

    /**
     * A file or folder around the subject: its root-relative path ({@code ""} for the root), how many file-to-file uses
     * it stands for, and whether it is on both sides — used by the subject and using it.
     */
    record Node(String rel, boolean folder, int count, boolean mutual) {}

    /**
     * What is around {@code subject}: the files or folders that use it ({@code users}) and those it uses ({@code uses}),
     * the most uses first; how many distinct files each side stands for; and how many files read are in the subject.
     */
    record Around(
            String subject,
            boolean folder,
            Grouping grouping,
            List<Node> users,
            List<Node> uses,
            int userFiles,
            int usedFiles,
            int insideFiles) {}

    /** One part of a folder in its matrix: a subfolder or a file of its own, with how many files read it holds. */
    record Part(String rel, boolean folder, int files) {}

    /**
     * How the parts of {@code folder} use each other: {@code cells[r][c]} is how many of row {@code r}'s files use a
     * file of column {@code c}; {@code cycle[r][c]} marks the uses between parts in the same cycle; {@code cycles} is
     * how many cycles there are; {@code left} how many parts were left out past {@link #MAX_PARTS}.
     */
    record Matrix(String folder, List<Part> parts, int[][] cells, boolean[][] cycle, int uses, int cycles, int left) {}

    static final ProjectMapDependencies EMPTY = new ProjectMapDependencies(List.of(), List.of(), false);

    private final Map<String, List<ProjectMapImports.Edge>> out = new HashMap<>();
    private final Map<String, List<ProjectMapImports.Edge>> in = new HashMap<>();
    private final java.util.NavigableSet<String> files;
    private final int edgeCount;
    private final boolean truncated;

    /**
     * {@code files}: every file read, root-relative; {@code edges}: which uses which; {@code truncated}: whether the
     * project had more files than were read.
     */
    ProjectMapDependencies(Collection<String> files, List<ProjectMapImports.Edge> edges, boolean truncated) {
        this.files = new TreeSet<>(files);
        for (ProjectMapImports.Edge edge : edges) {
            out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            in.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge);
        }
        this.edgeCount = edges.size();
        this.truncated = truncated;
    }

    /** How many files were read. */
    int fileCount() {
        return files.size();
    }

    /** How many file-to-file uses there are. */
    int edgeCount() {
        return edgeCount;
    }

    /** Whether the project had more files than were read. */
    boolean truncated() {
        return truncated;
    }

    /** Whether the file at {@code rel} was read (its language is one the card reads). */
    boolean read(String rel) {
        return files.contains(rel);
    }

    /** Whether {@code rel} is the folder {@code folder} or in it; every path is in the root ({@code ""}). */
    static boolean inside(String rel, String folder) {
        return folder.isEmpty() || rel.equals(folder) || rel.startsWith(folder + "/");
    }

    /** The files read in the folder {@code folder}, or {@code folder} itself when it is a file. */
    private List<String> filesOf(String subject, boolean folder) {
        if (!folder) {
            return files.contains(subject) ? List.of(subject) : List.of();
        }
        String from = subject.isEmpty() ? "" : subject + "/";
        return List.copyOf(files.subSet(from, from + Character.MAX_VALUE));
    }

    /** What uses {@code subject} and what it uses, as {@code grouping} says. */
    Around around(String subject, boolean folder, Grouping grouping) {
        List<String> mine = filesOf(subject, folder);
        Map<String, Integer> users = new LinkedHashMap<>();
        Map<String, Integer> uses = new LinkedHashMap<>();
        Set<String> userFiles = new TreeSet<>();
        Set<String> usedFiles = new TreeSet<>();
        for (String file : mine) {
            for (ProjectMapImports.Edge edge : out.getOrDefault(file, List.of())) {
                if (!folder || !inside(edge.to(), subject)) {
                    uses.merge(key(edge.to(), grouping), 1, Integer::sum);
                    usedFiles.add(edge.to());
                }
            }
            for (ProjectMapImports.Edge edge : in.getOrDefault(file, List.of())) {
                if (!folder || !inside(edge.from(), subject)) {
                    users.merge(key(edge.from(), grouping), 1, Integer::sum);
                    userFiles.add(edge.from());
                }
            }
        }
        boolean folders = grouping == Grouping.FOLDERS;
        return new Around(
                subject,
                folder,
                grouping,
                nodes(users, uses.keySet(), folders),
                nodes(uses, users.keySet(), folders),
                userFiles.size(),
                usedFiles.size(),
                folder ? mine.size() : 0);
    }

    /**
     * The files {@code subject} uses ({@code uses}) and those that use it ({@code users}), each once; for a folder,
     * only the files outside it. The Map's dependency overlay tints their rows.
     */
    record Neighbours(Set<String> uses, Set<String> users) {}

    /** The files around {@code subject}, the file or folder at that root-relative path. */
    Neighbours neighbours(String subject, boolean folder) {
        Set<String> uses = new TreeSet<>();
        Set<String> users = new TreeSet<>();
        for (String file : filesOf(subject, folder)) {
            for (ProjectMapImports.Edge edge : out.getOrDefault(file, List.of())) {
                if (!folder || !inside(edge.to(), subject)) {
                    uses.add(edge.to());
                }
            }
            for (ProjectMapImports.Edge edge : in.getOrDefault(file, List.of())) {
                if (!folder || !inside(edge.from(), subject)) {
                    users.add(edge.from());
                }
            }
        }
        return new Neighbours(Set.copyOf(uses), Set.copyOf(users));
    }

    private static String key(String file, Grouping grouping) {
        return grouping == Grouping.FOLDERS ? ProjectMapImports.folderOf(file) : file;
    }

    private static List<Node> nodes(Map<String, Integer> counts, Set<String> other, boolean folders) {
        List<Node> nodes = new ArrayList<>();
        counts.forEach((rel, count) -> nodes.add(new Node(rel, folders, count, other.contains(rel))));
        nodes.sort(Comparator.comparingInt(Node::count).reversed().thenComparing(Node::rel));
        return nodes;
    }

    /**
     * The file-to-file uses behind {@code node} around {@code subject}: of the subject's files by it ({@code incoming})
     * or by the subject's files of it, in path and line order.
     */
    List<ProjectMapImports.Edge> why(String subject, boolean folder, Node node, boolean incoming) {
        List<ProjectMapImports.Edge> found = new ArrayList<>();
        for (String file : filesOf(subject, folder)) {
            List<ProjectMapImports.Edge> edges = (incoming ? in : out).getOrDefault(file, List.of());
            for (ProjectMapImports.Edge edge : edges) {
                String other = incoming ? edge.from() : edge.to();
                if (folder && inside(other, subject)) {
                    continue;
                }
                if (node.folder() ? ProjectMapImports.folderOf(other).equals(node.rel()) : other.equals(node.rel())) {
                    found.add(edge);
                }
            }
        }
        found.sort(EDGE_ORDER);
        return found;
    }

    private static final Comparator<ProjectMapImports.Edge> EDGE_ORDER = Comparator.comparing(
                    ProjectMapImports.Edge::from)
            .thenComparingInt(ProjectMapImports.Edge::line)
            .thenComparing(ProjectMapImports.Edge::to);

    /**
     * The folder whose matrix is shown for {@code folder}: it, or — while all its files read are in one subfolder —
     * that subfolder, and so on down ({@code src/main/java/com/acme} rather than {@code src}).
     */
    String matrixFolder(String folder) {
        String current = folder;
        while (true) {
            List<String> mine = filesOf(current, true);
            if (mine.isEmpty()) {
                return current;
            }
            String only = null;
            for (String file : mine) {
                String part = partOf(file, current);
                if (part.equals(file) || only != null && !only.equals(part)) {
                    return current;
                }
                only = part;
            }
            if (only == null) {
                return current;
            }
            current = only;
        }
    }

    /** The part of {@code folder} that {@code file} (in it) is in: its subfolder or itself. */
    static String partOf(String file, String folder) {
        String rest = folder.isEmpty() ? file : file.substring(folder.length() + 1);
        int slash = rest.indexOf('/');
        return slash < 0 ? file : (folder.isEmpty() ? "" : folder + "/") + rest.substring(0, slash);
    }

    /** How the parts of {@code folder} use each other (the folder as {@link #matrixFolder} finds it). */
    Matrix matrix(String requested) {
        String folder = matrixFolder(requested);
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (String file : filesOf(folder, true)) {
            sizes.merge(partOf(file, folder), 1, Integer::sum);
        }
        Map<String, Map<String, Integer>> uses = new HashMap<>();
        Map<String, Integer> degree = new HashMap<>();
        for (String file : filesOf(folder, true)) {
            String from = partOf(file, folder);
            for (ProjectMapImports.Edge edge : out.getOrDefault(file, List.of())) {
                if (!inside(edge.to(), folder)) {
                    continue;
                }
                String to = partOf(edge.to(), folder);
                if (!to.equals(from)) {
                    uses.computeIfAbsent(from, k -> new HashMap<>()).merge(to, 1, Integer::sum);
                    degree.merge(from, 1, Integer::sum);
                    degree.merge(to, 1, Integer::sum);
                }
            }
        }
        List<String> kept = new ArrayList<>(sizes.keySet());
        kept.sort(Comparator.comparingInt((String part) -> degree.getOrDefault(part, 0))
                .reversed()
                .thenComparing(part -> part));
        int left = Math.max(0, kept.size() - MAX_PARTS);
        if (left > 0) {
            kept = new ArrayList<>(kept.subList(0, MAX_PARTS));
        }
        List<String> order = layered(kept, uses);
        int n = order.size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(order.get(i), i);
        }
        int[] component = components(order, uses, index);
        int[][] cells = new int[n][n];
        boolean[][] cycle = new boolean[n][n];
        int total = 0;
        for (int r = 0; r < n; r++) {
            for (Map.Entry<String, Integer> use :
                    uses.getOrDefault(order.get(r), Map.of()).entrySet()) {
                Integer c = index.get(use.getKey());
                if (c != null) {
                    cells[r][c] = use.getValue();
                    cycle[r][c] = component[r] == component[c];
                    total += use.getValue();
                }
            }
        }
        Map<Integer, Integer> componentSizes = new HashMap<>();
        for (int c : component) {
            componentSizes.merge(c, 1, Integer::sum);
        }
        int cycles =
                (int) componentSizes.values().stream().filter(size -> size > 1).count();
        List<Part> parts = new ArrayList<>();
        for (String part : order) {
            parts.add(new Part(part, !files.contains(part), sizes.getOrDefault(part, 0)));
        }
        return new Matrix(folder, List.copyOf(parts), cells, cycle, total, cycles, left);
    }

    /** The file-to-file uses behind a matrix cell: of {@code from}'s files (in {@code folder}) using {@code to}'s. */
    List<ProjectMapImports.Edge> why(String folder, String from, String to) {
        List<ProjectMapImports.Edge> found = new ArrayList<>();
        for (String file : filesOf(from, !files.contains(from))) {
            for (ProjectMapImports.Edge edge : out.getOrDefault(file, List.of())) {
                if (inside(edge.to(), folder) && partOf(edge.to(), folder).equals(to)) {
                    found.add(edge);
                }
            }
        }
        found.sort(EDGE_ORDER);
        return found;
    }

    /**
     * {@code parts} in an order where each uses only parts after it, but for parts in a cycle, which stay together: the
     * parts nothing uses first, then those only they use, and so on; names break ties.
     */
    private static List<String> layered(List<String> parts, Map<String, Map<String, Integer>> uses) {
        Map<String, Integer> index = new HashMap<>();
        List<String> sorted = new ArrayList<>(parts);
        sorted.sort(null);
        for (int i = 0; i < sorted.size(); i++) {
            index.put(sorted.get(i), i);
        }
        int[] component = components(sorted, uses, index);
        int count = 0;
        for (int c : component) {
            count = Math.max(count, c + 1);
        }
        int[] used = new int[count];
        List<Set<Integer>> next = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            next.add(new TreeSet<>());
        }
        for (int r = 0; r < sorted.size(); r++) {
            for (String to : uses.getOrDefault(sorted.get(r), Map.of()).keySet()) {
                Integer c = index.get(to);
                if (c != null
                        && component[c] != component[r]
                        && next.get(component[r]).add(component[c])) {
                    used[component[c]]++;
                }
            }
        }
        // The first part of each component, by name, stands for it.
        int[] first = new int[count];
        java.util.Arrays.fill(first, Integer.MAX_VALUE);
        for (int i = 0; i < sorted.size(); i++) {
            first[component[i]] = Math.min(first[component[i]], i);
        }
        java.util.PriorityQueue<Integer> ready = new java.util.PriorityQueue<>(Comparator.comparingInt(c -> first[c]));
        for (int c = 0; c < count; c++) {
            if (used[c] == 0) {
                ready.add(c);
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            int c = ready.poll();
            for (int i = 0; i < sorted.size(); i++) {
                if (component[i] == c) {
                    order.add(sorted.get(i));
                }
            }
            for (int to : next.get(c)) {
                if (--used[to] == 0) {
                    ready.add(to);
                }
            }
        }
        return order;
    }

    /** Each part's strongly connected component (Tarjan's, without recursion), numbered from 0. */
    private static int[] components(
            List<String> parts, Map<String, Map<String, Integer>> uses, Map<String, Integer> index) {
        int n = parts.size();
        int[] low = new int[n];
        int[] number = new int[n];
        int[] component = new int[n];
        boolean[] onStack = new boolean[n];
        java.util.Arrays.fill(number, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        int counter = 0;
        int components = 0;
        List<int[]> edges = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            List<Integer> targets = new ArrayList<>();
            for (String to : uses.getOrDefault(parts.get(r), Map.of()).keySet()) {
                Integer c = index.get(to);
                if (c != null && c != r) {
                    targets.add(c);
                }
            }
            targets.sort(null);
            edges.add(targets.stream().mapToInt(Integer::intValue).toArray());
        }
        for (int start = 0; start < n; start++) {
            if (number[start] >= 0) {
                continue;
            }
            Deque<int[]> work = new ArrayDeque<>(); // {node, next edge}
            work.push(new int[] {start, 0});
            number[start] = low[start] = counter++;
            stack.push(start);
            onStack[start] = true;
            while (!work.isEmpty()) {
                int[] frame = work.peek();
                int v = frame[0];
                int[] targets = edges.get(v);
                if (frame[1] < targets.length) {
                    int w = targets[frame[1]++];
                    if (number[w] < 0) {
                        number[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        work.push(new int[] {w, 0});
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], number[w]);
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    int parent = work.peek()[0];
                    low[parent] = Math.min(low[parent], low[v]);
                }
                if (low[v] == number[v]) {
                    while (true) {
                        int w = stack.pop();
                        onStack[w] = false;
                        component[w] = components;
                        if (w == v) {
                            break;
                        }
                    }
                    components++;
                }
            }
        }
        return component;
    }
}
