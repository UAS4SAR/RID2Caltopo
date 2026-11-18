# Device install pre-check

`install_precheck.py` is read-only. It never kills, force-stops, clears or uninstalls the app.
Run it immediately before an approved in-place install and install only on exit 0.

| Exit | Meaning |
|---|---|
| 0 | Safe to install |
| 1 | Not safe: a session appears active |
| 2 | The check could not be done reliably: do not install, report the output |

```sh
tools/device_install/install_precheck.py android R52Y90C9XST      # A5 Pro
tools/device_install/install_precheck.py ipad 00008103-0010596E1AD3001E
tools/device_install/install_precheck.py --self-test
```

**Android:** `adb -s <serial> shell netstat -tn` and `-tln` (full adb path, no `ss`, errors
shown). Safe when nothing is ESTABLISHED on the relay ports 1935, 8554, 8888, 8889 and 8189.

**iPad:** `devicectl device info processes`. Safe when RID2CaltopoApple is not listed. iOS
keeps a quit app's process suspended and may prewarm an app that was never opened, so a
listed process is also safe when either:

- no app log belongs to the current launch: the newest session log
  (`Documents/RID2Caltopo/FlightStorage/<day>/Log_*.txt`, read with `devicectl device copy
  from`) was written by another pid, so the process never reached the app's launch code; or
- the current launch's log ends with `Primary application session cleanup completed` (Quit,
  window swipe or idle timeout) and no `Application UI started` follows it.

Anything else (an open session, or a log that cannot be read) is not safe.
