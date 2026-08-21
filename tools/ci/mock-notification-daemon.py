#!/usr/bin/env python3
"""§17.5's "a CI container with a mock notification daemon".

Claims `org.freedesktop.Notifications` on whatever session bus is in
`DBUS_SESSION_BUS_ADDRESS` and answers the handful of methods a notifier
actually calls, so `notify-send` — and anything else speaking the freedesktop
notification spec — succeeds on a headless runner with no desktop environment.
Every notification it accepts is appended to the file named by
`SKYWARD_MOCK_NOTIFICATION_LOG` (one JSON object per line), which is how a
test asserts that the reminder text really made it across DBus rather than
only that the call returned.

Pure `jeepney` on purpose: it is the one DBus implementation for Python that
needs no compiled bindings and no system packages, so `pip install jeepney` is
the whole setup — the alternative, a real notification daemon, drags in X11 or
Wayland for something that is meant to be a stub.

Run it in the background inside a `dbus-run-session`:

    dbus-run-session -- bash -c \\
      'python3 tools/ci/mock-notification-daemon.py & ./gradlew :desktopApp:test'

It prints "ready" on stdout once it owns the name, so a caller that needs to
avoid the race can wait for that line instead of sleeping.
"""

import json
import os
import sys

from jeepney import HeaderFields, MessageType, new_error, new_method_return
from jeepney.bus_messages import message_bus
from jeepney.io.blocking import open_dbus_connection

BUS_NAME = "org.freedesktop.Notifications"
OBJECT_PATH = "/org/freedesktop/Notifications"
INTERFACE = "org.freedesktop.Notifications"

# DBUS_NAME_FLAG_DO_NOT_QUEUE: if something else already owns the name, fail
# loudly rather than sitting in a queue that never comes up — a silently
# queued mock is a test that silently tests nothing.
DO_NOT_QUEUE = 4
REQUEST_NAME_PRIMARY_OWNER = 1


def log_notification(record):
    path = os.environ.get("SKYWARD_MOCK_NOTIFICATION_LOG")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record) + "\n")
        handle.flush()


def main():
    connection = open_dbus_connection(bus="SESSION")
    reply = connection.send_and_get_reply(message_bus.RequestName(BUS_NAME, DO_NOT_QUEUE))
    if reply.body[0] != REQUEST_NAME_PRIMARY_OWNER:
        print(f"could not own {BUS_NAME}: RequestName returned {reply.body[0]}", file=sys.stderr)
        return 1

    print("ready", flush=True)
    next_id = 1

    while True:
        try:
            message = connection.receive()
        except (ConnectionResetError, EOFError):
            # The session bus went away — `dbus-run-session` tearing down after
            # the command it wrapped finished. That is this daemon's normal end
            # of life, not a failure, and it must not colour the CI step red.
            return 0
        if message.header.message_type != MessageType.method_call:
            continue
        fields = message.header.fields
        member = fields.get(HeaderFields.member)
        interface = fields.get(HeaderFields.interface)

        if interface == "org.freedesktop.DBus.Introspectable" and member == "Introspect":
            connection.send(new_method_return(message, "s", (INTROSPECTION,)))
        elif interface != INTERFACE:
            connection.send(new_error(message, "org.freedesktop.DBus.Error.UnknownInterface"))
        elif member == "GetCapabilities":
            connection.send(new_method_return(message, "as", (["body", "actions"],)))
        elif member == "GetServerInformation":
            connection.send(
                new_method_return(message, "ssss", ("skyward-mock", "skyward", "1.0", "1.2"))
            )
        elif member == "CloseNotification":
            connection.send(new_method_return(message, "", ()))
        elif member == "Notify":
            app_name, replaces_id, icon, summary, body, actions, hints, timeout = message.body
            log_notification(
                {
                    "app_name": app_name,
                    "summary": summary,
                    "body": body,
                    "actions": list(actions),
                    "timeout": timeout,
                }
            )
            notification_id = replaces_id or next_id
            next_id += 1
            connection.send(new_method_return(message, "u", (notification_id,)))
        else:
            connection.send(new_error(message, "org.freedesktop.DBus.Error.UnknownMethod"))


INTROSPECTION = """<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
<node>
  <interface name="org.freedesktop.Notifications">
    <method name="GetCapabilities"><arg direction="out" type="as"/></method>
    <method name="Notify">
      <arg direction="in" type="s"/><arg direction="in" type="u"/>
      <arg direction="in" type="s"/><arg direction="in" type="s"/>
      <arg direction="in" type="s"/><arg direction="in" type="as"/>
      <arg direction="in" type="a{sv}"/><arg direction="in" type="i"/>
      <arg direction="out" type="u"/>
    </method>
    <method name="CloseNotification"><arg direction="in" type="u"/></method>
    <method name="GetServerInformation">
      <arg direction="out" type="s"/><arg direction="out" type="s"/>
      <arg direction="out" type="s"/><arg direction="out" type="s"/>
    </method>
  </interface>
</node>
"""


if __name__ == "__main__":
    sys.exit(main())
