package cloudflare

import (
	"bytes"
	"context"
	"fmt"
	"github.com/goccy/go-json"
	"net/http"
	"time"
)

type CloudflareApi struct {
	client http.Client
}

func NewCloudflareApi(opts ...CloudflareApiOption) *CloudflareApi {
	api := &CloudflareApi{http.Client{Timeout: 30 * time.Second}}
	for _, opt := range opts {
		opt(api)
	}
	return api
}

func (api *CloudflareApi) CreateProfile(ctx context.Context, publicKey string) (*CloudflareProfile, error) {
	serial, err := GenerateRandomAndroidSerial()
	if err != nil {
		return nil, fmt.Errorf("failed to generate serial: %v", err)
	}
	data := Registration{
		Key:       publicKey,
		InstallID: "",
		FcmToken:  "",
		Tos:       TimeAsCfString(time.Now()),
		Model:     "PC",
		Serial:    serial,
		OsVersion: "",
		KeyType:   KeyTypeWg,
		TunType:   TunTypeWg,
		Locale:    "en-US",
	}
	jsonData, err := json.Marshal(data)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal json: %v", err)
	}
	request, err := http.NewRequest("POST", ApiUrl+"/"+ApiVersion+"/reg", bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, err
	}
	for k, v := range Headers {
		request.Header.Set(k, v)
	}
	response, err := api.client.Do(request.WithContext(ctx))
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to register: %v", response.StatusCode)
	}
	profile := new(CloudflareProfile)
	return profile, json.NewDecoder(response.Body).Decode(profile)
}

func (api *CloudflareApi) EnrollKey(ctx context.Context, authToken string, id string, keyType, tunType, publicKey string) (*CloudflareProfile, error) {
	deviceUpdate := DeviceUpdate{
		Name:    "PC",
		Key:     publicKey,
		KeyType: keyType,
		TunType: tunType,
	}
	jsonData, err := json.Marshal(deviceUpdate)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal json: %v", err)
	}
	request, err := http.NewRequest("PATCH", ApiUrl+"/"+ApiVersion+"/reg/"+id, bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, err
	}
	for k, v := range Headers {
		request.Header.Set(k, v)
	}
	request.Header.Set("Authorization", "Bearer "+authToken)
	response, err := api.client.Do(request.WithContext(ctx))
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to enroll key: %v", response.StatusCode)
	}
	profile := new(CloudflareProfile)
	return profile, json.NewDecoder(response.Body).Decode(profile)
}

func (api *CloudflareApi) GetProfile(ctx context.Context, authToken string, id string) (*CloudflareProfile, error) {
	request, err := http.NewRequest("GET", ApiUrl+"/"+ApiVersion+"/reg/"+id, nil)
	if err != nil {
		return nil, err
	}
	for k, v := range Headers {
		request.Header.Set(k, v)
	}
	request.Header.Set("Authorization", "Bearer "+authToken)
	response, err := api.client.Do(request.WithContext(ctx))
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to get profile: %v", response.StatusCode)
	}
	profile := new(CloudflareProfile)
	return profile, json.NewDecoder(response.Body).Decode(profile)
}
