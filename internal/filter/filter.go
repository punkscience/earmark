package filter

import (
	"fmt"
	"strings"
	"unicode"
)

// Expr is a boolean expression node evaluated against a lowercased file path.
type Expr interface {
	eval(lowerPath string) bool
}

type termExpr struct{ term string } // pre-lowercased

func (t termExpr) eval(lowerPath string) bool {
	return strings.Contains(lowerPath, t.term)
}

type andExpr struct{ left, right Expr }

func (a andExpr) eval(lowerPath string) bool {
	return a.left.eval(lowerPath) && a.right.eval(lowerPath)
}

type orExpr struct{ left, right Expr }

func (o orExpr) eval(lowerPath string) bool {
	return o.left.eval(lowerPath) || o.right.eval(lowerPath)
}

// FilterExpr returns the subset of files where expr matches the file's path
// (case-insensitive).
func FilterExpr(files []string, expr Expr) []string {
	var matched []string
	for _, path := range files {
		if expr.eval(strings.ToLower(path)) {
			matched = append(matched, path)
		}
	}
	return matched
}

// ParseExpr parses a boolean keyword expression into an Expr tree.
//
// Grammar (AND binds tighter than OR):
//
//	expr     = or_expr
//	or_expr  = and_expr ("OR" and_expr)*
//	and_expr = atom ("AND" atom)*
//	atom     = "(" expr ")" | term
//	term     = quoted_string | word
func ParseExpr(input string) (Expr, error) {
	tokens, err := tokenize(input)
	if err != nil {
		return nil, err
	}
	if len(tokens) == 0 {
		return nil, fmt.Errorf("empty expression")
	}
	p := &parser{tokens: tokens}
	expr, err := p.parseOr()
	if err != nil {
		return nil, err
	}
	if p.pos < len(p.tokens) {
		return nil, fmt.Errorf("unexpected token %q", p.tokens[p.pos].val)
	}
	return expr, nil
}

type tokKind int

const (
	tokWord tokKind = iota
	tokAnd
	tokOr
	tokLParen
	tokRParen
)

type token struct {
	kind tokKind
	val  string
}

func tokenize(input string) ([]token, error) {
	var tokens []token
	runes := []rune(input)
	n := len(runes)
	i := 0
	for i < n {
		switch {
		case unicode.IsSpace(runes[i]):
			i++
		case runes[i] == '(':
			tokens = append(tokens, token{kind: tokLParen})
			i++
		case runes[i] == ')':
			tokens = append(tokens, token{kind: tokRParen})
			i++
		case runes[i] == '"':
			j := i + 1
			for j < n && runes[j] != '"' {
				j++
			}
			if j >= n {
				return nil, fmt.Errorf("unterminated quoted string")
			}
			val := strings.ToLower(string(runes[i+1 : j]))
			tokens = append(tokens, token{kind: tokWord, val: val})
			i = j + 1
		default:
			j := i
			for j < n && !unicode.IsSpace(runes[j]) && runes[j] != '(' && runes[j] != ')' && runes[j] != '"' {
				j++
			}
			word := string(runes[i:j])
			switch strings.ToUpper(word) {
			case "AND":
				tokens = append(tokens, token{kind: tokAnd})
			case "OR":
				tokens = append(tokens, token{kind: tokOr})
			default:
				tokens = append(tokens, token{kind: tokWord, val: strings.ToLower(word)})
			}
			i = j
		}
	}
	return tokens, nil
}

type parser struct {
	tokens []token
	pos    int
}

func (p *parser) peek() (token, bool) {
	if p.pos >= len(p.tokens) {
		return token{}, false
	}
	return p.tokens[p.pos], true
}

func (p *parser) consume() token {
	t := p.tokens[p.pos]
	p.pos++
	return t
}

func (p *parser) parseOr() (Expr, error) {
	left, err := p.parseAnd()
	if err != nil {
		return nil, err
	}
	for {
		tok, ok := p.peek()
		if !ok || tok.kind != tokOr {
			break
		}
		p.consume()
		right, err := p.parseAnd()
		if err != nil {
			return nil, err
		}
		left = orExpr{left, right}
	}
	return left, nil
}

func (p *parser) parseAnd() (Expr, error) {
	left, err := p.parseAtom()
	if err != nil {
		return nil, err
	}
	for {
		tok, ok := p.peek()
		if !ok || tok.kind != tokAnd {
			break
		}
		p.consume()
		right, err := p.parseAtom()
		if err != nil {
			return nil, err
		}
		left = andExpr{left, right}
	}
	return left, nil
}

func (p *parser) parseAtom() (Expr, error) {
	tok, ok := p.peek()
	if !ok {
		return nil, fmt.Errorf("unexpected end of expression")
	}
	if tok.kind == tokLParen {
		p.consume()
		expr, err := p.parseOr()
		if err != nil {
			return nil, err
		}
		closing, ok := p.peek()
		if !ok || closing.kind != tokRParen {
			return nil, fmt.Errorf("missing closing parenthesis")
		}
		p.consume()
		return expr, nil
	}
	if tok.kind == tokWord {
		p.consume()
		return termExpr{tok.val}, nil
	}
	return nil, fmt.Errorf("unexpected token in expression")
}

// Filter returns the subset of files where at least one keyword is a
// case-insensitive substring of the file's absolute path.
func Filter(files []string, keywords []string) []string {
	lower := lowerAll(keywords)
	var matched []string
	for _, path := range files {
		if matches(path, lower) {
			matched = append(matched, path)
		}
	}
	return matched
}

func lowerAll(ss []string) []string {
	out := make([]string, len(ss))
	for i, s := range ss {
		out[i] = strings.ToLower(s)
	}
	return out
}

func matches(path string, lowerKeywords []string) bool {
	lowerPath := strings.ToLower(path)
	for _, kw := range lowerKeywords {
		if strings.Contains(lowerPath, kw) {
			return true
		}
	}
	return false
}