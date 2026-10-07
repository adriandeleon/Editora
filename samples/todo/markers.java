// Every built-in keyword, one per line. Each gets its own color in the editor and its own group in the
// TODO tool window. The class is deliberately not public, so the file name need not match it.
class Markers {
    // TODO: implement the happy path
    // FIXME: this NPEs when items is null
    // HACK: sleeps for 50 ms to dodge a race
    // NOTE: callers must hold the lock
    // XXX: revisit before release
    // DONE: validate the input
    // todo: lowercase is not a match (keywords are case-sensitive)
    // TODOS: neither is a longer word (keywords match whole words)
    void work() {
        String s = "TODO inside a string literal still counts";
        /* TODO: block comments are scanned too */
    }
}
