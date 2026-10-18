package nativeimage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command-line parsing of the native-image scripts, after Python's {@code argparse}: {@code --name value} or
 * {@code --name=value} options, valueless flags, positional arguments, {@code -h}/{@code --help}, and a usage
 * error that exits 2.
 */
final class Args {

    private final String prog;
    private final String usage;
    private final Map<String, String> values = new HashMap<>();
    private final Set<String> flags = new HashSet<>();
    private final List<String> positional = new ArrayList<>();

    /**
     * Parses {@code argv}. {@code usage} is the text after {@code "usage: "}, starting with the program name;
     * {@code options} and {@code flagNames} are the accepted {@code --names}; each of {@code positionals} names
     * a required positional argument.
     */
    Args(String usage, String description, String[] argv, List<String> options, List<String> flagNames,
            List<String> positionals) {
        this.usage = usage;
        this.prog = usage.split(" ", 2)[0];
        boolean onlyPositional = false;
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (onlyPositional || !arg.startsWith("-") || arg.equals("-")) {
                positional.add(arg);
            } else if (arg.equals("--")) {
                onlyPositional = true;
            } else if (arg.equals("-h") || arg.equals("--help")) {
                System.out.println("usage: " + usage + "\n\n" + description);
                System.exit(0);
            } else {
                int eq = arg.indexOf('=');
                String name = eq < 0 ? arg : arg.substring(0, eq);
                if (flagNames.contains(name) && eq < 0) {
                    flags.add(name);
                } else if (options.contains(name)) {
                    if (eq >= 0) {
                        values.put(name, arg.substring(eq + 1));
                    } else if (i + 1 < argv.length) {
                        values.put(name, argv[++i]);
                    } else {
                        error("argument " + name + ": expected one argument");
                    }
                } else {
                    error("unrecognized arguments: " + arg);
                }
            }
        }
        int expected = positionals.size();
        if (positional.size() > expected) {
            error("unrecognized arguments: " + String.join(" ", positional.subList(expected, positional.size())));
        }
        if (positional.size() < expected) {
            error("the following arguments are required: "
                    + String.join(", ", positionals.subList(positional.size(), expected)));
        }
    }

    /** Prints the usage and {@code message} to standard error and exits 2, as {@code argparse} does. */
    void error(String message) {
        System.err.println("usage: " + usage);
        System.err.println(prog + ": error: " + message);
        System.exit(2);
    }

    String positional(int index) {
        return positional.get(index);
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    String get(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    String required(String name) {
        String value = values.get(name);
        if (value == null) {
            error("the following arguments are required: " + name);
        }
        return value;
    }

    Path path(String name) {
        String value = values.get(name);
        return value == null ? null : Path.of(value);
    }

    int integer(String name, int fallback) {
        String value = values.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            error("argument " + name + ": invalid int value: '" + value + "'");
            return fallback;
        }
    }

    double decimal(String name, double fallback) {
        String value = values.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            error("argument " + name + ": invalid float value: '" + value + "'");
            return fallback;
        }
    }
}
