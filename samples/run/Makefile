# Run-feature demo — click the green ▶ in the gutter on any target line to run `make <target>`.
# Every recipe is a harmless @echo, so running any target is instant and has no side effects.
# "Run File" (right-click, or C-c r) on this buffer runs the default goal (bare `make`) → `all`.

GREETING ?= hello

all: greet build          ## the default goal — this is what "Run File" runs
	@echo "all: done"

greet:
	@echo "greet: $(GREETING) from make"

build:
	@echo "build: compiling (pretend)..."
	@echo "build: linking (pretend)..."

test: build
	@echo "test: 3 passed, 0 failed"

clean:
	@echo "clean: nothing real to remove (demo)"

# These get NO gutter ▶: a .PHONY declaration, a pattern rule, and the variable assignment above.
.PHONY: all greet build test clean

%.o: %.c
	@echo "pattern rule (not runnable): $< -> $@"
