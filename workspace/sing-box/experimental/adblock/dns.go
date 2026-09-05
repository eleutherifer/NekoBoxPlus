//go:build with_adblock

package adblock

import (
	"context"
	"net"
	"strings"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"golang.org/x/net/publicsuffix"
)

const blockedDNSResponseTTL = 60

func (s *Service) CheckDNSResponse(ctx context.Context, message *mDNS.Msg, response *mDNS.Msg) {
	if !s.options.FilterDNS() || response == nil || response.Rcode != mDNS.RcodeSuccess {
		s.debugContext(ctx, "DNS response skipped: filtering disabled, empty response, or non-success response")
		return
	}

	engineRef, engine := s.readyEngine()
	if engine == nil {
		s.debugContext(ctx, "DNS response skipped: engine is not ready")
		return
	}
	defer engineRef.release()

	if metadata := adapter.ContextFrom(ctx); metadata != nil && !s.constraintsMatch(metadata) {
		s.debugContext(ctx, "DNS response skipped: constraints mismatch")
		return
	}

	if len(message.Question) == 0 {
		s.debugContext(ctx, "DNS response skipped: no question")
		return
	}

	queryDomain := dnsNameToDomain(message.Question[0].Name)
	if queryDomain == "" {
		s.debugContext(ctx, "DNS response skipped: empty query domain")
		return
	}
	s.debugContext(ctx, "DNS response handling: ", queryDomain)

	// Always check the originally requested domain when DNS filtering is enabled.
	if checkResultBlocked(s.dnsCheck(engine, queryDomain)) {
		s.debugContext(ctx, "DNS response blocked original domain: ", queryDomain)
		s.blockDNSResponse(ctx, message, response, queryDomain)
		return
	}

	// If CNAME uncloaking is disabled, only the current domain is checked.
	if !s.options.Filtering.CNAMEUncloaking {
		s.debugContext(ctx, "DNS response allowed: CNAME uncloaking disabled, domain=", queryDomain)
		s.stats.recordRequest(false)
		return
	}

	// CNAME uncloaking is useful mainly for third-party aliases.
	// Skip it where CNAME cloaking is unlikely or impossible.
	if shouldSkipCNAMEUncloaking(queryDomain, response) {
		s.debugContext(ctx, "DNS response allowed: CNAME uncloaking skipped, domain=", queryDomain)
		s.stats.recordRequest(false)
		return
	}

	seen := map[string]bool{
		queryDomain: true,
	}

	var cnameDomains []string
	for _, rawAnswer := range response.Answer {
		cname, isCNAME := rawAnswer.(*mDNS.CNAME)
		if !isCNAME {
			continue
		}

		cnameDomain := dnsNameToDomain(cname.Target)
		if cnameDomain == "" || seen[cnameDomain] {
			continue
		}

		if s.shouldSkipCNAMECandidate(queryDomain, cnameDomain) {
			s.debugContext(ctx, "DNS CNAME candidate skipped: ", queryDomain, " -> ", cnameDomain)
			continue
		}

		seen[cnameDomain] = true
		cnameDomains = append(cnameDomains, cnameDomain)
	}

	for _, cnameDomain := range cnameDomains {
		if checkResultBlocked(s.dnsCheck(engine, cnameDomain)) {
			s.debugContext(ctx, "DNS response blocked CNAME: ", queryDomain, " -> ", cnameDomain)
			s.blockDNSResponse(ctx, message, response, queryDomain)
			return
		}
	}

	s.debugContext(ctx, "DNS response allowed: ", queryDomain)
	s.stats.recordRequest(false)
}

func (s *Service) dnsCheck(engine adblockrust.Engine, domain string) adblockrust.CheckResult {
	if domain == "" {
		return adblockrust.CheckResult{}
	}
	if domain[len(domain)-1] == '.' {
		domain = domain[:len(domain)-1]
	}
	requestURL := "http://" + domain + "/"
	requestHttpsURL := "https://" + domain + "/"
	result, err := s.requestCheck(engine, requestURL, "", "other", adblockrust.RequestMethodNone)
	if err != nil {
		s.debug("DNS domain check failed: ", domain, ", error: ", err)
		s.stats.recordRequest(false)
		return adblockrust.CheckResult{}
	}
	if checkResultBlocked(result) || result.Exception != "" {
		blocked := checkResultBlocked(result)
		s.debug("DNS domain check: ", domain, ", blocked: ", blocked)
		s.stats.recordRequest(blocked)
		return result
	}
	httpsResult, err := s.requestCheck(engine, requestHttpsURL, "", "other", adblockrust.RequestMethodNone)
	if err != nil {
		s.debug("DNS HTTPS domain check failed: ", domain, ", error: ", err)
		s.stats.recordRequest(false)
		return result
	}
	blocked := checkResultBlocked(httpsResult)
	s.debug("DNS domain check: ", domain, ", blocked: ", blocked)
	s.stats.recordRequest(blocked)
	if checkResultActionable(httpsResult) {
		return httpsResult
	}
	return result
}

func (s *Service) blockDNSResponse(ctx context.Context, message *mDNS.Msg, response *mDNS.Msg, queryDomain string) {
	metadata := adapter.InboundContext{
		Domain:   queryDomain,
		Protocol: C.ProtocolDNS,
	}
	if contextMetadata := adapter.ContextFrom(ctx); contextMetadata != nil {
		metadata = *contextMetadata
		metadata.Domain = queryDomain
		metadata.Protocol = C.ProtocolDNS
	}
	if s.logger != nil {
		s.logger.InfoContext(ctx, &blockedError{metadata: metadata})
	}
	s.debugContext(ctx, "DNS response rewrite: ", queryDomain)

	question := message.Question[0]
	response.Answer = nil
	response.Ns = nil
	response.Extra = nil

	ttl := s.options.Filtering.DNSBlockTTLValue()
	if ttl == 0 {
		ttl = blockedDNSResponseTTL
	}
	if s.options.Filtering.DNSBlockMode == option.AdblockDNSBlockModeNXDOMAIN {
		response.Rcode = mDNS.RcodeNameError
		return
	}
	header := mDNS.RR_Header{
		Name:  question.Name,
		Class: question.Qclass,
		Ttl:   ttl,
	}
	switch question.Qtype {
	case mDNS.TypeA:
		header.Rrtype = mDNS.TypeA
		response.Answer = []mDNS.RR{&mDNS.A{Hdr: header, A: net.IPv4zero}}
	case mDNS.TypeAAAA:
		header.Rrtype = mDNS.TypeAAAA
		response.Answer = []mDNS.RR{&mDNS.AAAA{Hdr: header, AAAA: net.IPv6zero}}
	}
}

func dnsNameToDomain(name string) string {
	name = mDNS.Fqdn(name)
	if len(name) <= 1 {
		return ""
	}
	return name[:len(name)-1]
}

func shouldSkipCNAMEUncloaking(queryDomain string, response *mDNS.Msg) bool {
	if isSecondLevelDomain(queryDomain) {
		return true
	}

	for _, rawAnswer := range response.Answer {
		if _, isCNAME := rawAnswer.(*mDNS.CNAME); isCNAME {
			return false
		}
	}

	return true
}

func (s *Service) shouldSkipCNAMECandidate(queryDomain string, cnameDomain string) bool {
	if queryDomain == cnameDomain {
		return true
	}

	// Same-site aliases are usually infrastructure aliases, not CNAME cloaking.
	// Example:
	//   www.example.com -> edge.example.com
	if sameRegistrableDomain(queryDomain, cnameDomain) {
		return true
	}

	// Skip known CDN / infrastructure targets.
	if s.isKnownInfrastructureDomain(cnameDomain) {
		return true
	}

	return false
}

var knownInfrastructureSuffixes = []string{
	"akamai.net",
	"akamaiedge.net",
	"akadns.net",
	"cloudfront.net",
	"cloudflare.net",
	"fastly.net",
	"fastlylb.net",
	"edgesuite.net",
	"edgekey.net",
	"azureedge.net",
	"trafficmanager.net",
	"amazonaws.com",
	"cdn77.org",
	"cdn77.net",
	"stackpathdns.com",
	"hwcdn.net",
	"cachefly.net",
}

func isKnownInfrastructureDomain(domain string) bool {
	domain = strings.TrimSuffix(strings.ToLower(domain), ".")

	for _, suffix := range knownInfrastructureSuffixes {
		if domain == suffix || strings.HasSuffix(domain, "."+suffix) {
			return true
		}
	}

	return false
}

func (s *Service) isKnownInfrastructureDomain(domain string) bool {
	if isKnownInfrastructureDomain(domain) {
		return true
	}
	domain = strings.TrimSuffix(strings.ToLower(domain), ".")
	for _, suffix := range s.options.Filtering.CNAMEInfrastructureSuffixes {
		suffix = strings.TrimSuffix(strings.ToLower(suffix), ".")
		if suffix != "" && (domain == suffix || strings.HasSuffix(domain, "."+suffix)) {
			return true
		}
	}
	return false
}

func isSecondLevelDomain(domain string) bool {
	labels := strings.Split(domain, ".")
	return len(labels) == 2
}

func registrableDomainFallback(domain string) string {
	labels := strings.Split(domain, ".")
	if len(labels) <= 2 {
		return domain
	}
	return strings.Join(labels[len(labels)-2:], ".")
}

func sameRegistrableDomain(a string, b string) bool {
	aETLD1, aErr := publicsuffix.EffectiveTLDPlusOne(a)
	bETLD1, bErr := publicsuffix.EffectiveTLDPlusOne(b)

	if aErr != nil || bErr != nil {
		return registrableDomainFallback(a) == registrableDomainFallback(b)
	}

	return aETLD1 == bETLD1
}

func isKnownApexLikeDomain(domain string) bool {
	labels := strings.Split(domain, ".")
	if len(labels) > 2 {
		return isKnownInfrastructureDomain(domain)
	}

	return isKnownInfrastructureDomain(domain)
}
