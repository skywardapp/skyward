#!/bin/sh
# Launcher on PATH for the jlinked tree installed under /app/skyward (§15.5).
# `exec` so the app is PID 1's direct child: the portal and the session
# manager both track the process they started, and an intervening shell would
# make "is Skyward still running?" answer wrong.
exec /app/skyward/bin/skyward "$@"
