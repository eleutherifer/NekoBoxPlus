package libcore

import (
	"fmt"
	"regexp"
	"slices"
	"strconv"

	C "github.com/sagernet/sing-box/constant"
	json "github.com/sagernet/sing/common/json"
)

type ConfigNormalizeResult struct {
	Result     string
	violations []string
}

func (r *ConfigNormalizeResult) GetViolationCount() int32 {
	if r == nil {
		return 0
	}
	return int32(len(r.violations))
}

func (r *ConfigNormalizeResult) GetViolation(index int32) string {
	if r == nil || index < 0 || int(index) >= len(r.violations) {
		return ""
	}
	return r.violations[index]
}

func NormalizeConfig(configContent string) *ConfigNormalizeResult {
	result, violations, normalized := normalizeConfig(configContent)
	if !normalized {
		return &ConfigNormalizeResult{}
	}
	return &ConfigNormalizeResult{
		Result:     result,
		violations: violations,
	}
}

var outboundErrorPatterns = []*regexp.Regexp{
	regexp.MustCompile(`(?:^|: )outbounds\[([0-9]+)\](?:[.:]|$)`),
	regexp.MustCompile(`(?:^|: )initialize outbound\[([0-9]+)\](?:[.:]|$)`),
}

func normalizeConfig(configContent string) (string, []string, bool) {
	migratedContent, migrationViolations, migrated := migrateConfig114(configContent)
	if !migrated {
		return "", nil, false
	}
	configContent = migratedContent
	if checkConfigForNormalization(configContent) == nil {
		return configContent, migrationViolations, true
	}

	root, outbounds, err := parseRawConfig(configContent)
	if err != nil {
		return "", nil, false
	}
	if err = stabilizeOutboundTags(outbounds); err != nil {
		return "", nil, false
	}

	violations := migrationViolations
	for len(outbounds) > 0 {
		candidate, marshalErr := marshalRawConfig(root, outbounds)
		if marshalErr != nil {
			return "", nil, false
		}
		validationErr := checkConfigForNormalization(candidate)
		if validationErr == nil {
			return candidate, violations, true
		}

		index, loaded := outboundErrorIndex(validationErr.Error())
		if !loaded || index >= len(outbounds) {
			return "", nil, false
		}
		profileCollection, participates := outboundProfileCollection(outbounds, index)
		if !participates {
			return "", nil, false
		}
		violations = append(violations, validationErr.Error())

		removedTag, tagErr := outboundTag(outbounds[index], index)
		if tagErr != nil {
			return "", nil, false
		}
		outbounds = slices.Delete(outbounds, index, index+1)
		var cascadeViolations []string
		var removedTags map[string]struct{}
		outbounds, cascadeViolations, removedTags, err = cascadeRemovedOutbound(outbounds, removedTag)
		if err != nil {
			return "", nil, false
		}
		violations = append(violations, cascadeViolations...)
		if !profileCollectionHasOutbound(outbounds, profileCollection) {
			return "", nil, false
		}
		if routeFinalReferencesRemoved(root, removedTags) || dnsReferencesRemovedOutbound(root, removedTags) {
			return "", nil, false
		}
	}

	candidate, err := marshalRawConfig(root, outbounds)
	if err != nil || checkConfigForNormalization(candidate) != nil {
		return "", nil, false
	}
	return candidate, violations, true
}

func checkConfigForNormalization(configContent string) error {
	instance, err := newSingBoxInstance(configContent, nil, true)
	if err != nil {
		return err
	}
	_ = instance.Close()
	return nil
}

func parseRawConfig(configContent string) (map[string]json.RawMessage, []json.RawMessage, error) {
	root, err := json.UnmarshalExtended[map[string]json.RawMessage]([]byte(configContent))
	if err != nil {
		return nil, nil, err
	}
	var outbounds []json.RawMessage
	if rawOutbounds, loaded := root["outbounds"]; loaded {
		err = json.Unmarshal(rawOutbounds, &outbounds)
		if err != nil {
			return nil, nil, err
		}
	}
	return root, outbounds, nil
}

func marshalRawConfig(root map[string]json.RawMessage, outbounds []json.RawMessage) (string, error) {
	rawOutbounds, err := json.Marshal(outbounds)
	if err != nil {
		return "", err
	}
	root["outbounds"] = rawOutbounds
	content, err := json.Marshal(root)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func stabilizeOutboundTags(outbounds []json.RawMessage) error {
	for index, rawOutbound := range outbounds {
		object, err := rawObject(rawOutbound)
		if err != nil {
			return err
		}
		var tag string
		if rawTag, loaded := object["tag"]; loaded {
			if err = json.Unmarshal(rawTag, &tag); err != nil {
				continue
			}
		}
		if tag != "" {
			continue
		}
		object["tag"], err = json.Marshal(strconv.Itoa(index))
		if err != nil {
			return err
		}
		outbounds[index], err = json.Marshal(object)
		if err != nil {
			return err
		}
	}
	return nil
}

func outboundErrorIndex(message string) (int, bool) {
	for _, pattern := range outboundErrorPatterns {
		matches := pattern.FindStringSubmatch(message)
		if len(matches) != 2 {
			continue
		}
		index, err := strconv.Atoi(matches[1])
		return index, err == nil
	}
	return 0, false
}

func outboundTag(rawOutbound json.RawMessage, fallbackIndex int) (string, error) {
	object, err := rawObject(rawOutbound)
	if err != nil {
		return "", err
	}
	var tag string
	if rawTag, loaded := object["tag"]; loaded {
		if err = json.Unmarshal(rawTag, &tag); err != nil {
			return strconv.Itoa(fallbackIndex), nil
		}
	}
	if tag == "" {
		tag = strconv.Itoa(fallbackIndex)
	}
	return tag, nil
}

func outboundProfileCollection(outbounds []json.RawMessage, targetIndex int) (map[string]struct{}, bool) {
	targetTag, err := outboundTag(outbounds[targetIndex], targetIndex)
	if err != nil {
		return nil, false
	}
	connections := make(map[string]map[string]struct{})
	connect := func(left string, right string) {
		if left == "" || right == "" {
			return
		}
		if connections[left] == nil {
			connections[left] = make(map[string]struct{})
		}
		if connections[right] == nil {
			connections[right] = make(map[string]struct{})
		}
		connections[left][right] = struct{}{}
		connections[right][left] = struct{}{}
	}

	for index, rawOutbound := range outbounds {
		outbound, objectErr := rawObject(rawOutbound)
		if objectErr != nil {
			continue
		}
		tag, tagErr := outboundTag(rawOutbound, index)
		if tagErr != nil {
			continue
		}
		outboundType, typeErr := rawString(outbound, "type")
		if typeErr == nil && (outboundType == C.TypeSelector || outboundType == C.TypeURLTest) {
			members, membersErr := rawStringList(outbound, "outbounds")
			if membersErr == nil {
				for _, member := range members {
					connect(tag, member)
				}
			}
		}
		detour, detourErr := rawString(outbound, "detour")
		if detourErr == nil {
			connect(tag, detour)
		}
	}
	if len(connections[targetTag]) == 0 {
		return nil, false
	}

	collection := make(map[string]struct{})
	pending := []string{targetTag}
	for len(pending) > 0 {
		tag := pending[0]
		pending = pending[1:]
		if _, loaded := collection[tag]; loaded {
			continue
		}
		collection[tag] = struct{}{}
		for connectedTag := range connections[tag] {
			pending = append(pending, connectedTag)
		}
	}
	return collection, true
}

func profileCollectionHasOutbound(outbounds []json.RawMessage, collection map[string]struct{}) bool {
	for index, rawOutbound := range outbounds {
		tag, err := outboundTag(rawOutbound, index)
		if err != nil {
			continue
		}
		if _, loaded := collection[tag]; loaded {
			return true
		}
	}
	return false
}

func cascadeRemovedOutbound(outbounds []json.RawMessage, removedTag string) ([]json.RawMessage, []string, map[string]struct{}, error) {
	removedTags := make(map[string]struct{})
	pending := []string{removedTag}
	var violations []string
	for len(pending) > 0 {
		currentTag := pending[0]
		pending = pending[1:]
		if _, loaded := removedTags[currentTag]; loaded {
			continue
		}
		removedTags[currentTag] = struct{}{}

		for index := 0; index < len(outbounds); {
			object, err := rawObject(outbounds[index])
			if err != nil {
				return nil, nil, nil, err
			}
			outboundType, err := rawString(object, "type")
			if err != nil {
				index++
				continue
			}
			tag, err := outboundTag(outbounds[index], index)
			if err != nil {
				return nil, nil, nil, err
			}

			if outboundType == C.TypeSelector || outboundType == C.TypeURLTest {
				members, membersErr := rawStringList(object, "outbounds")
				if membersErr != nil {
					index++
					continue
				}
				filtered := slices.DeleteFunc(slices.Clone(members), func(member string) bool {
					return member == currentTag
				})
				if len(filtered) == len(members) {
					index++
					continue
				}
				if len(filtered) == 0 {
					violations = append(violations, fmt.Sprintf("outbound %q has no valid outbounds", tag))
					outbounds = slices.Delete(outbounds, index, index+1)
					pending = append(pending, tag)
					continue
				}
				object["outbounds"], err = json.Marshal(filtered)
				if err != nil {
					return nil, nil, nil, err
				}
				defaultTag, defaultErr := rawString(object, "default")
				if defaultErr == nil && defaultTag == currentTag {
					delete(object, "default")
				}
				outbounds[index], err = json.Marshal(object)
				if err != nil {
					return nil, nil, nil, err
				}
				index++
				continue
			}

			detour, err := rawString(object, "detour")
			if err != nil {
				index++
				continue
			}
			if detour != currentTag {
				index++
				continue
			}
			violations = append(violations, fmt.Sprintf("outbound %q depends on removed outbound %q", tag, currentTag))
			outbounds = slices.Delete(outbounds, index, index+1)
			pending = append(pending, tag)
		}
	}
	return outbounds, violations, removedTags, nil
}

func routeFinalReferencesRemoved(root map[string]json.RawMessage, removedTags map[string]struct{}) bool {
	rawRoute, loaded := root["route"]
	if !loaded {
		return false
	}
	route, err := rawObject(rawRoute)
	if err != nil {
		return true
	}
	finalTag, err := rawString(route, "final")
	if err != nil {
		return true
	}
	_, removed := removedTags[finalTag]
	return removed
}

func dnsReferencesRemovedOutbound(root map[string]json.RawMessage, removedTags map[string]struct{}) bool {
	rawDNS, loaded := root["dns"]
	if !loaded {
		return false
	}
	dnsOptions, err := rawObject(rawDNS)
	if err != nil {
		return true
	}
	rawServers, loaded := dnsOptions["servers"]
	if !loaded {
		return false
	}
	var servers []json.RawMessage
	if err = json.Unmarshal(rawServers, &servers); err != nil {
		return true
	}
	return dnsServersReferenceRemovedOutbound(servers, removedTags)
}

func dnsServersReferenceRemovedOutbound(servers []json.RawMessage, removedTags map[string]struct{}) bool {
	for _, rawServer := range servers {
		server, err := rawObject(rawServer)
		if err != nil {
			return true
		}
		detour, err := rawString(server, "detour")
		if err != nil {
			return true
		}
		if _, removed := removedTags[detour]; removed {
			return true
		}

		rawChildren, loaded := server["servers"]
		if !loaded {
			continue
		}
		var children []json.RawMessage
		if err = json.Unmarshal(rawChildren, &children); err != nil {
			return true
		}
		if dnsServersReferenceRemovedOutbound(children, removedTags) {
			return true
		}
	}
	return false
}

func rawObject(content json.RawMessage) (map[string]json.RawMessage, error) {
	var object map[string]json.RawMessage
	err := json.Unmarshal(content, &object)
	return object, err
}

func rawString(object map[string]json.RawMessage, key string) (string, error) {
	rawValue, loaded := object[key]
	if !loaded {
		return "", nil
	}
	var value string
	err := json.Unmarshal(rawValue, &value)
	return value, err
}

func rawStringList(object map[string]json.RawMessage, key string) ([]string, error) {
	rawValue, loaded := object[key]
	if !loaded {
		return nil, nil
	}
	var value []string
	err := json.Unmarshal(rawValue, &value)
	return value, err
}
