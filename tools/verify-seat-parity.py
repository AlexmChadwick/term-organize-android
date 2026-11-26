# Copyright (C) 2026 Term Organize contributors.
# SPDX-License-Identifier: AGPL-3.0-only

from pathlib import Path
text = Path('docs/PARITY.md').read_text()
comment = Path('app/src/main/java/app/termorganize/core/model/Models.kt').read_text()
assert 'zero or negative' in text
assert '"Full" and web shows "0 of 0 open"' in text
assert 'positive reported seats remaining' in text
assert '"N open" and web shows "N of 0 open"' in text
assert "Android's capacity wording documented in docs/PARITY.md" in comment
print('Both zero-capacity branches and getter policy reference verified')
