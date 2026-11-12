package tls

import (
	"crypto/ecdh"
	"crypto/rand"
	"github.com/metacubex/utls/internal/mlkem"
)

// GenerateRealityHybridKeyShare installs matching private keys for a REALITY hybrid share.
func (keys *KeySharePrivateKeys) GenerateRealityHybridKeyShare() (KeyShare, error) {
	kem, err := mlkem.GenerateKey768()
	if err != nil {
		return KeyShare{}, err
	}
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return KeyShare{}, err
	}
	keys.Mlkem, keys.MlkemEcdhe = kem, key
	return KeyShare{Group: X25519MLKEM768, Data: append(kem.EncapsulationKey().Bytes(), key.PublicKey().Bytes()...)}, nil
}
