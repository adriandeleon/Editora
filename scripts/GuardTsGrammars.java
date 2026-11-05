/// Re-applies Editora's local patch to the bundled TypeScript and TypeScriptReact grammars: a word-start
/// guard in front of the identifier that begins (or nearly begins) each of the regexes below.
///
/// A JDK 25 compact source file (JEP 512) — run it from the repo root after vendoring a new copy of
/// either grammar:
///   java scripts/GuardTsGrammars.java          # patch both files in place
///   java scripts/GuardTsGrammars.java --check  # exit 1 if a file still needs it (nothing written)
///
/// Why: these regexes start with an identifier, `([_$[:alpha:]][_$[:alnum:]]*)`, followed by a lookahead.
/// The regex engine tries every start position in a line, and from each one inside a long word the
/// identifier runs to the end of the word, the lookahead fails, and it backs off one letter at a time.
/// So a run of N letters cost N²: a 2,000-letter word in a string or a statement took ~0.2–0.5 s to colour,
/// 10,000 letters ~7–8 s. `(?<![_$[:alnum:]])` turns away a start in the middle of a word at once.
///
/// The guard never changes a match. A match that started mid-word would end where the same word's
/// earlier start ends, before the same lookahead, so that earlier start (tried first) matches too.
/// (The exception is a name glued to digits before it, `9abc`, which is not JavaScript.)
/// Checked token for token on 180,000 lines of JS/TS/JSX/TSX: no difference.
///
/// Each fragment is regex text as the grammar has it, with ⟨G⟩ where the guard goes; every occurrence
/// is patched (several rules share a regex). A fragment that is neither found nor already patched
/// stops the script: upstream changed that regex, so re-measure (BundledGrammarsLongWordTest) and
/// update the list.

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

static final String GUARD = "(?<![_$[:alnum:]])";
static final String MARK = "⟨G⟩";

static final List<String> FRAGMENTS = List.of(
        // object-member: shorthand property `a,`
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\\s*(?=,|\\}|$|\\/\\/|\\/\\*)",
        // object-member: `name: function`/arrow
        "(?x)(?:(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\\s*(?=(\\/\\*(",
        // object-member: `name =` in an object literal
        "(?=⟨G⟩[_$[:alpha:]][_$[:alnum:]]*\\s*=)",
        // label `name: {`
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\\s*(:)(?=\\s*\\{)",
        // object-identifiers: `Name.prototype`
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(?=\\s*\\??\\.\\s*prototype\\b",
        // object-identifiers: `Name.member`
        "(?x)(?:\n  (⟨G⟩[[:upper:]][_$[:digit:][:upper:]]*) |\n  (⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\n)(?=\\s*\\??\\.",
        // type-name, class heritage, import-equals: `Name.`
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\\s*(?:(\\.)|(\\?\\.(?!\\s*[[:digit:]])))",
        // type-name: `Name<`
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\\s*(<)",
        // function-call: `name<` before a call
        "(?=((((⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(\\s*\\??\\.\\s*(\\#?[_$[:alpha:]]",
        // template-call: `a.b.tag<T>` and `tag<T>` before a template
        "(?=((⟨G⟩[_$[:alpha:]][_$[:alnum:]]*\\s*\\??\\.\\s*)*|(\\??\\.\\s*)?)(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(<\\s*",
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)?\\s*(?=(<\\s*",
        // template and template-type: tagged template `tag`
        "(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)?(`)",
        // var-single-const, var-single-variable: `name = function`/arrow
        "(?x)(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(?=\\s*\n",
        "(?x)(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(\\!)?(?=\\s*\n",
        // identifiers: `a.name = function`/arrow
        "(?x)(?:(?:(\\.)|(\\?\\.(?!\\s*[[:digit:]])))\\s*)?(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(?=\\s*=\\s*(",
        // arrow-function: `name =>`
        "(\\basync)\\s+)?(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)\\s*(?==>)",
        // field-declaration: `#name?:` and its begin lookahead
        "(?x)(\\#?⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)(?:(\\?)|(\\!))?(?=",
        "|(\\#?⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)|(\\'",
        // method-declaration-name and its kin: `name(`
        ")(?!\\$))|(⟨G⟩[_$[:alpha:]][_$[:alnum:]]*)|(\\'",
        // instanceof-expr end: `x instanceof`
        "(([\\&\\~\\^\\|]\\s*)?⟨G⟩[_$[:alpha:]][_$[:alnum:]]*\\s+instanceof",
        // jsx-tag-attribute-name (TypeScriptReact only): `ns:name=`
        "(?:(⟨G⟩[_$[:alpha:]][-_$[:alnum:].]*)(:))?\n  (⟨G⟩[_$[:alpha:]][-_$[:alnum:]]*)\n  (?=\\s|=|/?>|/\\*|//)");

/** Regex text as it is written inside a JSON string. */
static String json(String regex) {
    return regex.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
}

void main(String[] args) throws Exception {
    boolean check = args.length > 0 && args[0].equals("--check");
    Path dir = Path.of("src/main/resources/com/editora/grammars");
    boolean stale = false;
    Map<Path, String> out = new LinkedHashMap<>();
    for (String name : List.of("typescript", "typescriptreact")) {
        Path file = dir.resolve(name + ".tmLanguage.json");
        String text = Files.readString(file);
        int patched = 0;
        for (String fragment : FRAGMENTS) {
            String before = json(fragment.replace(MARK, ""));
            String after = json(fragment.replace(MARK, GUARD));
            int todo = count(text, before);
            int done = count(text, after);
            if (todo == 0 && done == 0) {
                if (fragment.contains("[-_$") && name.equals("typescript")) {
                    continue; // JSX attributes are TypeScriptReact's alone
                }
                IO.println(name + ": not found, upstream changed this regex: " + fragment);
                System.exit(2);
            }
            text = text.replace(before, after);
            patched += todo;
        }
        IO.println(name + ": " + patched + " regex fragment(s) " + (check ? "need" : "got") + " the guard");
        if (patched > 0) {
            stale = true;
            out.put(file, text);
        }
    }
    if (check && stale) {
        System.exit(1);
    }
    if (!check) {
        for (var e : out.entrySet()) {
            Files.writeString(e.getKey(), e.getValue()); // only once every fragment was found in both files
        }
    }
}

static int count(String text, String part) {
    int n = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
        n++;
    }
    return n;
}
