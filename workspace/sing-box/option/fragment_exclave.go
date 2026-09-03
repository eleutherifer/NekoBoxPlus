package option

type FragmentExclaveOutboundOptions struct {
	DialerOptions
	TLSRecordFragmentation bool `json:"tls_record_fragmentation,omitempty"`
	TCPSegmentation        bool `json:"tcp_segmentation,omitempty"`
}
