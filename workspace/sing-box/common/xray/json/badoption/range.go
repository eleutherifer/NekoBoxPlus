package badoption

import (
	"bytes"
	"fmt"
	"github.com/goccy/go-json"
	"strconv"
	"strings"

	"github.com/sagernet/sing-box/common/xray/crypto"
	E "github.com/sagernet/sing/common/exceptions"
)

type Range struct {
	From int32 `json:"from"`
	To   int32 `json:"to"`
}

func (c *Range) Build() *Range {
	return (*Range)(c)
}

func (c *Range) MarshalJSON() ([]byte, error) {
	if c.From == c.To {
		return json.Marshal(c.From)
	}
	return json.Marshal(fmt.Sprintf("%d-%d", c.From, c.To))
}

func (c *Range) UnmarshalJSON(content []byte) error {
	if len(content) > 0 && content[0] != '"' && content[0] != '{' && content[0] != '[' && content[len(content)-1] != '"' {
		var buf bytes.Buffer
		buf.Grow(len(content) + 2)
		buf.WriteRune('"')
		buf.Write(content)
		buf.WriteRune('"')

		content = buf.Bytes()
	}

	if len(content) == 2 && content[0] == '"' && content[1] == '"' {
		*c = Range{0, 0}
		return nil
	}

	var rangeValue struct {
		From int32 `json:"from"`
		To   int32 `json:"to"`
	}
	var stringValue string
	err := json.Unmarshal(content, &stringValue)
	if err == nil {
		parts := strings.Split(stringValue, "-")
		if len(parts) != 2 {
			from, err := strconv.ParseInt(parts[0], 10, 32)
			if err != nil {
				return err
			}
			rangeValue.From, rangeValue.To = int32(from), int32(from)
		} else {
			from, err := strconv.ParseInt(parts[0], 10, 32)
			if err != nil {
				return err
			}
			to, err := strconv.ParseInt(parts[1], 10, 32)
			if err != nil {
				return err
			}
			rangeValue.From, rangeValue.To = int32(from), int32(to)
		}
	} else {
		var int32Value int32
		err := json.Unmarshal(content, &int32Value)
		if err == nil {
			rangeValue.From, rangeValue.To = int32Value, int32Value
		} else {
			err = json.Unmarshal(content, &rangeValue)
			if err != nil {
				return err
			}
		}
	}
	if rangeValue.From > rangeValue.To {
		return E.New("invalid range")
	}
	*c = Range{rangeValue.From, rangeValue.To}
	return nil
}

func (c *Range) String() string {
	if c.From == c.To {
		return strconv.FormatInt(int64(c.From), 10)
	}
	return fmt.Sprintf("%d-%d", c.From, c.To)
}

func (c Range) Rand() int32 {
	return int32(crypto.RandBetween(int64(c.From), int64(c.To)))
}
