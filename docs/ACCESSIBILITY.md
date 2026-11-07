# Accessibility

- The input field exposes a full editor-sized accessibility region, `contentDescription`
  (`CodeEditorLabels.editorDescription`), and `stateDescription` with the caret's line and column.
  The drawing layer is hidden from accessibility so it does not obscure the text field.
- Screen readers read the hidden input field, which holds the lines around the caret, not the whole
  document. Native screen-reader speech and navigation need platform-specific verification; the
  automated semantics and composition tests do not establish a VoiceOver or TalkBack journey.
- The completion list and find bar controls have labels. The Compose input field exposes localized
  Fold, Unfold, Fold all, and Unfold all accessibility actions when folding is enabled. Region actions
  choose the innermost applicable range at the caret. Collapsing moves a hidden selection to its header;
  folding also works in read-only editors. Action labels are in `CodeEditorLabels`.
- Active IME composition retains native key handling and marked-text selection, including reversed
  selections. Touch selection's toolbar uses root coordinates, including editors offset within a
  larger screen.
- Hover tooltips, find result counts, replace results, and invalid-pattern or time-out messages are
  polite live regions.
- All strings are in `CodeEditorLabels` for localization.

Native verification evidence and the harness that produces it live in
[verification/README.md](../verification/README.md).
