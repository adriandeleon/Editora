# Sample Makefile — targets, variables, functions, conditionals, recipes (real tabs).
CC      := cc
CFLAGS  ?= -O2 -Wall
SRCS    := $(wildcard src/*.c)
OBJS    := $(SRCS:.c=.o)
PREFIX  ?= /usr/local

.PHONY: all clean install

all: app

app: $(OBJS)
	$(CC) $(CFLAGS) -o $@ $^

%.o: %.c include/app.h
	$(CC) $(CFLAGS) -c $< -o $@

ifeq ($(OS),Windows_NT)
EXE := .exe
else
EXE :=
endif

install: app
	install -m 0755 app$(EXE) $(PREFIX)/bin/app

clean:
	rm -f app $(OBJS)   # continuation example follows
	@echo "cleaned: \
	all objects removed"
