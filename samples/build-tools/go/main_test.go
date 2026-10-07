package main

import "testing"

// `go test ./...` from the Go popup fills the Test Results tool window: one test passes, one fails on
// purpose, and one is skipped, so every status has an example. "Go: Related File" jumps between
// this file and main.go.

func TestGreetsByName(t *testing.T) {
	if got := greeting("Ada"); got != "Hello from Ada." {
		t.Errorf("greeting(%q) = %q", "Ada", got)
	}
}

func TestGreetingTable(t *testing.T) {
	for _, who := range []string{"Ada", "Grace"} {
		t.Run(who, func(t *testing.T) {
			if got := greeting(who); got == "" {
				t.Error("empty greeting")
			}
		})
	}
}

func TestFailsOnPurpose(t *testing.T) {
	if got := greeting("Ada"); got != "Goodbye from Ada." {
		t.Errorf("this failure is the sample's red test: got %q", got)
	}
}

func TestSkippedOnPurpose(t *testing.T) {
	t.Skip("skipped on purpose")
}
