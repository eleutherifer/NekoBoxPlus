package client

import "testing"

func TestUniqueResolverCountIgnoresDomains(t *testing.T) {
	connections := []Connection{
		{Domain: "one.example", ResolverLabel: "1.1.1.1:53"},
		{Domain: "two.example", ResolverLabel: "1.1.1.1:53"},
		{Domain: "one.example", ResolverLabel: "8.8.8.8:53"},
		{Domain: "ignored.example"},
	}

	if got := uniqueResolverCount(connections); got != 2 {
		t.Fatalf("uniqueResolverCount() = %d, want 2", got)
	}
}

func TestBalancerResolverCountsUseUniqueEndpoints(t *testing.T) {
	b := NewBalancer(0, nil)
	b.SetConnections([]*Connection{
		{Domain: "one.example", ResolverLabel: "1.1.1.1:53", Key: "a"},
		{Domain: "two.example", ResolverLabel: "1.1.1.1:53", Key: "b"},
		{Domain: "one.example", ResolverLabel: "8.8.8.8:53", Key: "c"},
	})
	b.SetConnectionValidity("a", true)
	b.SetConnectionValidity("b", true)

	if got := b.TotalResolverCount(); got != 2 {
		t.Fatalf("TotalResolverCount() = %d, want 2", got)
	}
	if got := b.ActiveResolverCount(); got != 1 {
		t.Fatalf("ActiveResolverCount() = %d, want 1", got)
	}
}

func TestReportAcceptedResolverProgressUsesUniqueWorkingResolvers(t *testing.T) {
	c := &Client{}
	var progress []ResolverProgress
	c.SetResolverProgressCallback(func(item ResolverProgress) {
		progress = append(progress, item)
	})

	counters := &mtuScanCounters{totalResolvers: 4}
	c.reportAcceptedResolverProgress(Connection{Domain: "one.example", ResolverLabel: "1.1.1.1:53"}, counters)
	c.reportAcceptedResolverProgress(Connection{Domain: "two.example", ResolverLabel: "1.1.1.1:53"}, counters)
	c.reportAcceptedResolverProgress(Connection{Domain: "one.example", ResolverLabel: "8.8.8.8:53"}, counters)
	c.reportAcceptedResolverProgress(Connection{Domain: "ignored.example"}, counters)

	if len(progress) != 2 {
		t.Fatalf("reported %d progress events, want 2", len(progress))
	}
	if progress[0].Found != 1 || progress[0].Total != 4 || progress[0].Resolver != "1.1.1.1:53" {
		t.Fatalf("first progress = %#v, want first working resolver count", progress[0])
	}
	if progress[1].Found != 2 || progress[1].Total != 4 || progress[1].Resolver != "8.8.8.8:53" {
		t.Fatalf("second progress = %#v, want second working resolver count", progress[1])
	}
}
