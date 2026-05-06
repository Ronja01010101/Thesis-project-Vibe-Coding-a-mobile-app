# Known Bugs

Bugs noticed during testing that aren't immediate blockers. They get fixed when Step priorities allow.

| ID | Noticed | Severity | Component | Description |
|---|---|---|---|---|
| BUG-001 | 2026-05-06 | Low | Search | Search field doesn't handle Swedish characters (å, ä, ö). Typing without diacritics doesn't match stops whose names contain them, and vice versa. Need locale-aware normalisation (e.g. `Collator` / NFD + diacritic strip) so `"angerm"` matches `"Ångermanland"`. |
