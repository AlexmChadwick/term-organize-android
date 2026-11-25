# Android and web SeatText parity

1. Capacity 0 or unknown: web prints "N of 0 open" for non-full sections, whereas Android prints "N open". Android's "N open" is the preferred behaviour for a later web port.
2. Over-capacity clamp: web prints counts above capacity verbatim (e.g. "20 of 15 open"), whereas Android clamps open seats to capacity (e.g. "15 of 15 open"). Android's clamp is the preferred behaviour for a later web port.
3. Separator: web row joins seat text and waitlist text with a middle dot separator (" · "), whereas Android joins them with a comma and space (", ").
