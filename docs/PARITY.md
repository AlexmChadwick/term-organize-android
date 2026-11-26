# Android and web SeatText parity

1. For zero or unknown capacity, when reported seats remaining are zero or negative, Android shows "Full" and web shows "0 of 0 open". With positive reported seats remaining, Android shows "N open" and web shows "N of 0 open". In both versions, a positive waitlist queue can reduce the effective open count N to zero. Android's wording is preferred for a later web port.
2. Over-capacity clamp: web prints counts above capacity verbatim (e.g. "20 of 15 open"), whereas Android clamps open seats to capacity (e.g. "15 of 15 open"). Android's clamp is the preferred behaviour for a later web port.
3. Separator: web row joins seat text and waitlist text with a middle dot separator (" · "), whereas Android joins them with a comma and space (", ").
