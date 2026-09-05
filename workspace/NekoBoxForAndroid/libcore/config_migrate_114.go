package libcore

import (
	"fmt"
	"net"
	"net/url"
	"strconv"

	json "github.com/sagernet/sing/common/json"
)

func migrateConfig114(configContent string) (string, []string, bool) {
	root, err := json.UnmarshalExtended[map[string]any]([]byte(configContent))
	if err != nil {
		return "", nil, false
	}
	var violations []string
	migrateLegacyDNS114(root, &violations)
	migrateRuleSetDownloads114(root, &violations)
	if len(violations) == 0 {
		return configContent, nil, true
	}
	content, err := json.Marshal(root)
	if err != nil {
		return "", nil, false
	}
	return string(content), violations, true
}

func migrateLegacyDNS114(root map[string]any, violations *[]string) {
	dns, ok := root["dns"].(map[string]any)
	if !ok {
		return
	}
	if _, loaded := dns["independent_cache"]; loaded {
		delete(dns, "independent_cache")
		*violations = append(*violations, "removed deprecated dns.independent_cache")
	}
	servers, ok := dns["servers"].([]any)
	if ok {
		for _, rawServer := range servers {
			server, serverOK := rawServer.(map[string]any)
			if !serverOK {
				continue
			}
			if _, typed := server["type"]; typed {
				continue
			}
			address, addressOK := server["address"].(string)
			if !addressOK || address == "" {
				continue
			}
			if migrateLegacyDNSServerAddress(server, address) {
				delete(server, "address")
				if resolver, loaded := server["address_resolver"].(string); loaded && resolver != "" {
					domainResolver := map[string]any{"server": resolver}
					if strategy, strategyLoaded := server["address_strategy"]; strategyLoaded {
						domainResolver["strategy"] = strategy
					}
					server["domain_resolver"] = domainResolver
				}
				delete(server, "address_resolver")
				delete(server, "address_strategy")
				*violations = append(*violations, "migrated legacy DNS server "+address)
			}
		}
	}
	if cache, cacheOK := nestedMap(root, "experimental", "cache_file"); cacheOK {
		if storeRDRC, loaded := cache["store_rdrc"].(bool); loaded {
			if storeRDRC {
				cache["store_dns"] = true
			}
			delete(cache, "store_rdrc")
			delete(cache, "rdrc_timeout")
			*violations = append(*violations, "migrated cache_file.store_rdrc to store_dns")
		}
	}
}

func migrateLegacyDNSServerAddress(server map[string]any, address string) bool {
	if address == "local" || address == "local://" {
		server["type"] = "local"
		return true
	}
	parsed, err := url.Parse(address)
	if err != nil {
		return false
	}
	if parsed.Scheme == "" {
		server["type"] = "udp"
		host, portText, splitErr := net.SplitHostPort(address)
		if splitErr == nil {
			server["server"] = host
			if port, portErr := strconv.Atoi(portText); portErr == nil {
				server["server_port"] = port
			}
		} else {
			server["server"] = address
		}
		if port, loaded := server["port"]; loaded {
			server["server_port"] = port
			delete(server, "port")
		}
		return true
	}
	typeName := parsed.Scheme
	switch typeName {
	case "udp", "tcp", "tls", "quic", "https", "h3":
	case "dhcp":
		server["type"] = typeName
		if parsed.Host != "" && parsed.Host != "auto" {
			server["interface"] = parsed.Host
		}
		return true
	default:
		return false
	}
	server["type"] = typeName
	server["server"] = parsed.Hostname()
	if parsed.Port() != "" {
		if port, portErr := strconv.Atoi(parsed.Port()); portErr == nil {
			server["server_port"] = port
		}
	}
	if (typeName == "https" || typeName == "h3") && parsed.Path != "" && parsed.Path != "/dns-query" {
		server["path"] = parsed.Path
	}
	return true
}

func migrateRuleSetDownloads114(root map[string]any, violations *[]string) {
	route, ok := root["route"].(map[string]any)
	if !ok {
		return
	}
	ruleSets, ok := route["rule_set"].([]any)
	if !ok {
		return
	}
	httpClients, _ := root["http_clients"].([]any)
	clientByDetour := make(map[string]string)
	for index, rawRuleSet := range ruleSets {
		ruleSet, ruleSetOK := rawRuleSet.(map[string]any)
		if !ruleSetOK || ruleSet["type"] != "remote" {
			continue
		}
		detour, loaded := ruleSet["download_detour"].(string)
		if !loaded || detour == "" {
			continue
		}
		clientTag, exists := clientByDetour[detour]
		if !exists {
			clientTag = fmt.Sprintf("migrated-ruleset-download-%d", index+1)
			clientByDetour[detour] = clientTag
			httpClients = append(httpClients, map[string]any{"tag": clientTag, "detour": detour})
		}
		ruleSet["http_client"] = clientTag
		delete(ruleSet, "download_detour")
		*violations = append(*violations, "migrated remote rule-set download_detour "+detour)
	}
	if len(clientByDetour) > 0 {
		root["http_clients"] = httpClients
	}
}

func nestedMap(root map[string]any, keys ...string) (map[string]any, bool) {
	current := root
	for _, key := range keys {
		next, ok := current[key].(map[string]any)
		if !ok {
			return nil, false
		}
		current = next
	}
	return current, true
}
