//go:build with_adblock

package assets

import (
	"bytes"
	_ "embed"
	"html/template"
	"log"
)

//go:embed error.html
var errorPage string

var errorPageTpl *template.Template

var ErrorPagesGenerator = "sing-box-plus"

type ErrorContext struct {
	Heading            string
	TitleHumanReadable string
	Description        string
	RawError           string
	URL                string
	Timestamp          string
	Generator          string
}

func init() {
	var err error
	errorPageTpl, err = template.New("page").Parse(string(errorPage))
	if err != nil {
		log.Fatalf("parse template: %v", err)
	}
}

func GetErrorPage(data ErrorContext) ([]byte, error) {
	if data.Generator == "" {
		data.Generator = ErrorPagesGenerator
	}
	var out bytes.Buffer
	if err := errorPageTpl.Execute(&out, data); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
