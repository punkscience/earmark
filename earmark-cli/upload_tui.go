package main

import (
	"fmt"
	"path/filepath"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

const (
	barWidth  = 24
	nameWidth = 28
)

var (
	titleStyle = lipgloss.NewStyle().Bold(true)
	barFill    = lipgloss.NewStyle().Foreground(lipgloss.Color("42"))
	barEmpty   = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))
	doneStyle  = lipgloss.NewStyle().Foreground(lipgloss.Color("42"))
	failStyle  = lipgloss.NewStyle().Foreground(lipgloss.Color("196"))
	dimStyle   = lipgloss.NewStyle().Foreground(lipgloss.Color("244"))
)

type fileState int

const (
	stPending fileState = iota
	stActive
	stDone
	stFailed
)

type uploadItem struct {
	name       string
	state      fileState
	phase      uploadPhase
	bytesDone  int64
	bytesTotal int64
	err        error
}

type uploadModel struct {
	items []uploadItem
	done  bool
}

// Messages emitted by the upload driver goroutine.
type startFileMsg struct{ index int }
type progressMsg struct {
	index int
	ev    uploadEvent
}
type fileDoneMsg struct {
	index int
	err   error
}
type allDoneMsg struct{}

func (m uploadModel) Init() tea.Cmd { return nil }

func (m uploadModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.KeyMsg:
		if msg.String() == "ctrl+c" {
			return m, tea.Quit
		}
	case startFileMsg:
		m.items[msg.index].state = stActive
	case progressMsg:
		it := &m.items[msg.index]
		it.phase = msg.ev.phase
		if msg.ev.bytesTotal > 0 {
			it.bytesTotal = msg.ev.bytesTotal
			it.bytesDone = msg.ev.bytesDone
		}
	case fileDoneMsg:
		it := &m.items[msg.index]
		if msg.err != nil {
			it.state = stFailed
			it.err = msg.err
		} else {
			it.state = stDone
			if it.bytesTotal > 0 {
				it.bytesDone = it.bytesTotal
			}
		}
	case allDoneMsg:
		m.done = true
		return m, tea.Quit
	}
	return m, nil
}

func (m uploadModel) View() string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n\n", titleStyle.Render(fmt.Sprintf("Earmarking %d file(s)", len(m.items))))
	for _, it := range m.items {
		b.WriteString(renderItem(it))
		b.WriteByte('\n')
	}
	if m.done {
		success, failed := 0, 0
		for _, it := range m.items {
			switch it.state {
			case stDone:
				success++
			case stFailed:
				failed++
			}
		}
		fmt.Fprintf(&b, "\n%d/%d earmarked successfully", success, len(m.items))
		if failed > 0 {
			fmt.Fprintf(&b, ", %s", failStyle.Render(fmt.Sprintf("%d failed", failed)))
		}
		b.WriteByte('\n')
	} else {
		fmt.Fprintf(&b, "\n%s\n", dimStyle.Render("Press ctrl+c to cancel."))
	}
	return b.String()
}

func renderItem(it uploadItem) string {
	name := truncName(it.name, nameWidth)

	var frac float64
	if it.bytesTotal > 0 {
		frac = float64(it.bytesDone) / float64(it.bytesTotal)
	}

	var status string
	switch it.state {
	case stPending:
		status = dimStyle.Render("pending")
	case stDone:
		frac = 1
		status = doneStyle.Render("done ✓")
	case stFailed:
		status = failStyle.Render("failed ✗")
	case stActive:
		switch it.phase {
		case phaseEncrypting:
			status = dimStyle.Render("encrypting…")
		case phaseDiscovering:
			status = dimStyle.Render("finding servers…")
		case phaseSaving:
			frac = 1
			status = dimStyle.Render("saving…")
		default: // phaseUploading
			status = fmt.Sprintf("%3.0f%%  %s/%s", frac*100,
				humanBytes(it.bytesDone), humanBytes(it.bytesTotal))
		}
	}

	return fmt.Sprintf("%-*s  %s  %s", nameWidth, name, renderBar(frac), status)
}

func renderBar(frac float64) string {
	if frac < 0 {
		frac = 0
	}
	if frac > 1 {
		frac = 1
	}
	filled := int(frac * float64(barWidth))
	if filled > barWidth {
		filled = barWidth
	}
	return barFill.Render(strings.Repeat("█", filled)) +
		barEmpty.Render(strings.Repeat("░", barWidth-filled))
}

func truncName(s string, max int) string {
	if len(s) <= max {
		return s
	}
	if max <= 1 {
		return s[:max]
	}
	return s[:max-1] + "…"
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%dB", n)
	}
	div, exp := int64(unit), 0
	for x := n / unit; x >= unit && exp < 3; x /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f%ciB", float64(n)/float64(div), "KMGT"[exp])
}

// uploadFilesTUI uploads files while rendering a live progress view.
func uploadFilesTUI(hexPrivKey string, paths []string) error {
	items := make([]uploadItem, len(paths))
	for i, p := range paths {
		items[i] = uploadItem{name: filepath.Base(p)}
	}
	p := tea.NewProgram(uploadModel{items: items})

	go func() {
		for i, path := range paths {
			p.Send(startFileMsg{index: i})
			err := uploadFile(hexPrivKey, path, func(ev uploadEvent) {
				p.Send(progressMsg{index: i, ev: ev})
			})
			p.Send(fileDoneMsg{index: i, err: err})
		}
		p.Send(allDoneMsg{})
	}()

	if _, err := p.Run(); err != nil {
		return err
	}
	return nil
}
