package com.editora.lsp;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Where common standard-library names come from, for "Import Path from pathlib" on a name Pyright reports as not
 * defined. Pyright offers an import only from modules already in the file's import graph (it has no index of the
 * standard library, which is Pylance's), so its completion had nothing for {@code Path} in a file that never imported
 * {@code pathlib} (Python review 2 E102). The list is short on purpose: names a Python programmer types every day,
 * each with the one module it is usually imported from.
 */
final class PythonStdlibNames {

    private PythonStdlibNames() {}

    /** Standard modules imported whole ({@code import os}); an undefined {@code os} is one of these. */
    static final Set<String> MODULES = Set.of(
            "abc",
            "argparse",
            "array",
            "ast",
            "asyncio",
            "base64",
            "bisect",
            "calendar",
            "collections",
            "concurrent",
            "configparser",
            "contextlib",
            "copy",
            "csv",
            "ctypes",
            "dataclasses",
            "datetime",
            "decimal",
            "difflib",
            "enum",
            "errno",
            "fnmatch",
            "fractions",
            "functools",
            "gc",
            "getpass",
            "glob",
            "gzip",
            "hashlib",
            "heapq",
            "hmac",
            "html",
            "http",
            "importlib",
            "inspect",
            "io",
            "ipaddress",
            "itertools",
            "json",
            "locale",
            "logging",
            "math",
            "mimetypes",
            "multiprocessing",
            "operator",
            "os",
            "pathlib",
            "pickle",
            "platform",
            "pprint",
            "queue",
            "random",
            "re",
            "secrets",
            "select",
            "shlex",
            "shutil",
            "signal",
            "socket",
            "sqlite3",
            "ssl",
            "stat",
            "statistics",
            "string",
            "struct",
            "subprocess",
            "sys",
            "tarfile",
            "tempfile",
            "textwrap",
            "threading",
            "time",
            "timeit",
            "tomllib",
            "traceback",
            "types",
            "typing",
            "unicodedata",
            "unittest",
            "urllib",
            "uuid",
            "warnings",
            "weakref",
            "xml",
            "zipfile",
            "zlib");

    private static final Map<String, String> FROM = new HashMap<>();

    private static void from(String module, String... names) {
        for (String n : names) {
            FROM.put(n, module);
        }
    }

    static {
        from("pathlib", "Path", "PurePath", "PurePosixPath", "PureWindowsPath");
        from("collections", "OrderedDict", "defaultdict", "Counter", "deque", "namedtuple", "ChainMap");
        from(
                "collections.abc",
                "Iterable",
                "Iterator",
                "Callable",
                "Generator",
                "Mapping",
                "MutableMapping",
                "Sequence",
                "MutableSequence",
                "Awaitable",
                "Coroutine",
                "AsyncIterable",
                "AsyncIterator",
                "AsyncGenerator",
                "Hashable",
                "Sized",
                "Container",
                "Collection");
        from(
                "typing",
                "Any",
                "Optional",
                "Union",
                "List",
                "Dict",
                "Tuple",
                "Set",
                "FrozenSet",
                "Type",
                "TypeVar",
                "Generic",
                "Protocol",
                "Literal",
                "Final",
                "ClassVar",
                "TypedDict",
                "NamedTuple",
                "NewType",
                "Annotated",
                "Self",
                "NoReturn",
                "Never",
                "TypeAlias",
                "ParamSpec",
                "Concatenate",
                "IO",
                "TextIO",
                "BinaryIO",
                "AnyStr",
                "TYPE_CHECKING",
                "cast",
                "overload",
                "override",
                "final",
                "runtime_checkable",
                "get_type_hints");
        from("dataclasses", "dataclass", "field", "asdict", "astuple", "fields", "InitVar");
        from("datetime", "date", "timedelta", "timezone");
        from("enum", "Enum", "IntEnum", "StrEnum", "Flag", "IntFlag", "auto");
        from(
                "functools",
                "partial",
                "reduce",
                "wraps",
                "lru_cache",
                "cache",
                "cached_property",
                "total_ordering",
                "singledispatch");
        from(
                "itertools",
                "chain",
                "groupby",
                "product",
                "permutations",
                "combinations",
                "islice",
                "cycle",
                "accumulate",
                "zip_longest",
                "starmap",
                "takewhile",
                "dropwhile",
                "pairwise",
                "batched");
        from(
                "contextlib",
                "contextmanager",
                "asynccontextmanager",
                "suppress",
                "closing",
                "ExitStack",
                "nullcontext",
                "redirect_stdout");
        from("abc", "ABC", "ABCMeta", "abstractmethod");
        from("decimal", "Decimal");
        from("fractions", "Fraction");
        from("uuid", "UUID", "uuid4");
        from("copy", "deepcopy");
        from("pprint", "pprint");
        from("textwrap", "dedent");
        from("operator", "itemgetter", "attrgetter");
        from("time", "sleep", "perf_counter", "monotonic");
        from("heapq", "heappush", "heappop", "heapify");
        from("bisect", "bisect_left", "bisect_right", "insort");
        from("math", "sqrt", "floor", "ceil", "isclose");
        from("io", "StringIO", "BytesIO");
        from("tempfile", "TemporaryDirectory", "NamedTemporaryFile", "mkdtemp");
        from("threading", "Thread", "Lock", "RLock", "Event");
        from("queue", "Queue");
        from("concurrent.futures", "ThreadPoolExecutor", "ProcessPoolExecutor", "as_completed");
        from("shutil", "rmtree", "copyfile", "which");
        from("glob", "glob");
        from("subprocess", "PIPE", "CalledProcessError");
        from("logging", "getLogger");
    }

    /** The module {@code name} is usually imported from ({@code Path} → {@code pathlib}), or null. */
    static @Nullable String moduleOf(String name) {
        return FROM.get(name);
    }
}
