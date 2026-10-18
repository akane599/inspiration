---
description: File writes go through Write/Edit
priority: 20
---
- Create and change files with the Write/Edit tools, not shell heredocs or inline python writers.
- If a hook blocks a shell write, redo it with Write/Edit; don't retry another shell variant (`tee`, `printf >`, `node -e`, scratch scripts).
