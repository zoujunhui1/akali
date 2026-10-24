package store

import "testing"

func TestParseCode(t *testing.T) {
	for in, want := range map[string]struct {
		level  string
		number int
	}{"B2-036": {"B2", 36}, "c1-006": {"C1", 6}, "B2-1": {"B2", 1}} {
		l, n, err := ParseCode(in)
		if err != nil || l != want.level || n != want.number {
			t.Errorf("ParseCode(%q) = %q %d %v", in, l, n, err)
		}
	}
	for _, bad := range []string{"", "B2", "B2-", "B2-abc", "-036", "B2-0", "TOOLONGLEVEL-1"} {
		if _, _, err := ParseCode(bad); err == nil {
			t.Errorf("ParseCode(%q) should fail", bad)
		}
	}
	if Code("B2", 6) != "B2-006" {
		t.Error("Code format")
	}
}
