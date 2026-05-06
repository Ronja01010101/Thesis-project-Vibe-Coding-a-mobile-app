# Known Bugs

Bugs noticed during testing that aren't immediate blockers. They get fixed when Step priorities allow.

| ID | Noticed | Severity | Component | Description |
|---|---|---|---|---|
| BUG-001 | 2026-05-06 | Low | Search | Search field doesn't handle Swedish characters (å, ä, ö). Typing without diacritics doesn't match stops whose names contain them, and vice versa. Need locale-aware normalisation (e.g. `Collator` / NFD + diacritic strip) so `"angerm"` matches `"Ångermanland"`. |
| BUG-002 | 2026-05-06 | Medium | Map / markers | When the map is zoomed out, the "400 nearest to center" markers all clump at the center while the rest of the visible map has no markers — looks unnatural. Possible fix: only render markers above a zoom threshold (e.g. zoom ≥ 12), or switch to true clustering (osmdroid-bonuspack `RadiusMarkerClusterer`) that shows summary icons when zoomed out and individual stops when zoomed in. |
| BUG-003 | 2026-05-06 | Low | Map / markers | Default OSMDroid stop marker icon is too large when zoomed out — markers visually overlap and obscure the map tiles. Use a smaller custom icon (small dot / drawable) and possibly scale with zoom level. |
