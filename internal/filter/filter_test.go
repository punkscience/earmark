package filter

import (
	"testing"
)

func TestParseExpr(t *testing.T) {
	files := []string{
		"/music/Jazz/Miles Davis/Kind of Blue.mp3",
		"/music/Jazz/Bill Evans/Waltz for Debby.flac",
		"/music/Classical/Bach/Goldberg Variations.flac",
		"/music/Rock/Led Zeppelin/Stairway.mp3",
	}

	tests := []struct {
		expr string
		want []string
	}{
		{
			expr: "jazz",
			want: []string{
				"/music/Jazz/Miles Davis/Kind of Blue.mp3",
				"/music/Jazz/Bill Evans/Waltz for Debby.flac",
			},
		},
		{
			expr: "jazz OR rock",
			want: []string{
				"/music/Jazz/Miles Davis/Kind of Blue.mp3",
				"/music/Jazz/Bill Evans/Waltz for Debby.flac",
				"/music/Rock/Led Zeppelin/Stairway.mp3",
			},
		},
		{
			expr: "jazz AND miles",
			want: []string{
				"/music/Jazz/Miles Davis/Kind of Blue.mp3",
			},
		},
		{
			expr: "jazz AND classical",
			want: nil,
		},
		{
			expr: "(jazz OR classical) AND flac",
			want: []string{
				"/music/Jazz/Bill Evans/Waltz for Debby.flac",
				"/music/Classical/Bach/Goldberg Variations.flac",
			},
		},
		{
			expr: `"kind of blue"`,
			want: []string{
				"/music/Jazz/Miles Davis/Kind of Blue.mp3",
			},
		},
	}

	for _, tc := range tests {
		expr, err := ParseExpr(tc.expr)
		if err != nil {
			t.Errorf("ParseExpr(%q) error: %v", tc.expr, err)
			continue
		}
		got := FilterExpr(files, expr)
		if len(got) != len(tc.want) {
			t.Errorf("FilterExpr(%q) = %v, want %v", tc.expr, got, tc.want)
			continue
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Errorf("FilterExpr(%q)[%d] = %q, want %q", tc.expr, i, got[i], tc.want[i])
			}
		}
	}
}

func TestParseExprErrors(t *testing.T) {
	cases := []string{
		"",
		`"unterminated`,
		"(jazz",
		"jazz AND",
		"OR jazz",
	}
	for _, input := range cases {
		_, err := ParseExpr(input)
		if err == nil {
			t.Errorf("ParseExpr(%q) expected error, got nil", input)
		}
	}
}