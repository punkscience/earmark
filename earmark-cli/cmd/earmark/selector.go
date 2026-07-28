package main

import (
	"fmt"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
)

// selectorItem represents a file in the interactive selection list.
type selectorItem struct {
	path     string
	selected bool
}

// selectorModel is the Bubble Tea model for interactive file selection.
type selectorModel struct {
	items    []selectorItem
	cursor   int
	quitting bool
}

func newSelectorModel(paths []string) selectorModel {
	items := make([]selectorItem, len(paths))
	for i, p := range paths {
		items[i] = selectorItem{path: p, selected: false}
	}
	return selectorModel{items: items}
}

func (m selectorModel) Init() tea.Cmd {
	return nil
}

func (m selectorModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "ctrl+c", "q", "esc":
			m.quitting = true
			// Clear all selections on quit.
			for i := range m.items {
				m.items[i].selected = false
			}
			return m, tea.Quit

		case "up", "k":
			if m.cursor > 0 {
				m.cursor--
			}

		case "down", "j":
			if m.cursor < len(m.items)-1 {
				m.cursor++
			}

		case " ":
			m.items[m.cursor].selected = !m.items[m.cursor].selected

		case "enter":
			if m.hasSelection() {
				return m, tea.Quit
			}
			// If nothing selected, toggle current (enter = select & confirm).
			m.items[m.cursor].selected = true
			return m, tea.Quit
		}
	}
	return m, nil
}

func (m selectorModel) View() string {
	var b strings.Builder
	b.WriteString("Select files to earmark (space=toggle, enter=confirm, q=quit):\n\n")

	// Show up to 20 items with scroll window.
	start, end := 0, len(m.items)
	if len(m.items) > 20 {
		half := 10
		start = m.cursor - half
		if start < 0 {
			start = 0
		}
		end = start + 20
		if end > len(m.items) {
			end = len(m.items)
			start = end - 20
		}
	}

	if start > 0 {
		fmt.Fprintf(&b, "  ... %d more above ...\n", start)
	}

	for i := start; i < end; i++ {
		cursor := " "
		if i == m.cursor {
			cursor = ">"
		}
		checked := "[ ]"
		if m.items[i].selected {
			checked = "[x]"
		}
		// Truncate display path for readability.
		display := m.items[i].path
		fmt.Fprintf(&b, "%s %s %s\n", cursor, checked, display)
	}

	if end < len(m.items) {
		fmt.Fprintf(&b, "  ... %d more below ...\n", len(m.items)-end)
	}

	selected := m.selectedCount()
	if selected > 0 {
		fmt.Fprintf(&b, "\n%d file(s) selected — press Enter to upload.\n", selected)
	} else {
		b.WriteString("\nPress Space to select files, Enter to confirm.\n")
	}
	return b.String()
}

func (m selectorModel) hasSelection() bool {
	for _, it := range m.items {
		if it.selected {
			return true
		}
	}
	return false
}

func (m selectorModel) selectedCount() int {
	n := 0
	for _, it := range m.items {
		if it.selected {
			n++
		}
	}
	return n
}

// selectedPaths returns paths of all selected items.
func (m selectorModel) selectedPaths() []string {
	var paths []string
	for _, it := range m.items {
		if it.selected {
			paths = append(paths, it.path)
		}
	}
	return paths
}

// runSelector runs the interactive file selector and returns the selected paths.
func runSelector(paths []string) ([]string, error) {
	if len(paths) == 0 {
		return nil, nil
	}
	model := newSelectorModel(paths)
	p := tea.NewProgram(model)
	final, err := p.Run()
	if err != nil {
		return nil, err
	}
	m := final.(selectorModel)
	if m.quitting && !m.hasSelection() {
		return nil, nil
	}
	return m.selectedPaths(), nil
}
