package congestion

import "testing"

func TestNewValidation(t *testing.T) {
	for _, name := range []string{"", "bbr", "cubic", "reno"} {
		factory, err := New(name, 0, nil)
		if err != nil {
			t.Fatalf("New(%q): %v", name, err)
		}
		if factory == nil {
			t.Fatalf("New(%q) returned a nil factory", name)
		}
	}
	if _, err := New("invalid", 0, nil); err == nil {
		t.Fatal("expected unknown controller error")
	}
	if _, err := New("bbr", -1, nil); err == nil {
		t.Fatal("expected negative cwnd error")
	}
}
