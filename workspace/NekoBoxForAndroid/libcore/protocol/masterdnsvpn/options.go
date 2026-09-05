package masterdnsvpn

type OutboundOptions struct {
	ConfigText string   `json:"config,omitempty"`
	Resolvers  []string `json:"resolvers,omitempty"`
	ProfileDir string   `json:"profile_dir,omitempty"`
}
