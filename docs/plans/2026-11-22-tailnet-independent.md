# Tailnet independent of the proxy tunnel (Android)

Status: implemented (Android). MagicDNS names work in tailnet-only mode from the start (option 2 below), as chosen.

Tested on the Samsung (Android 17, mobile data) against the user's real tailnet, using only this machine (salamander) as
the peer: tailnet-only mode reaches salamander by IP, short and full name, websites go out with the carrier IP,
salamander reaches a listener on the phone; proxy on → tailnet still works while the gate is closed, websites use the
proxy's IP; proxy off again → tailnet-only with the node never restarted; tailnet toggle off/on with the proxy off stops
and starts the VPN; `kill -9` → Android restarted the service straight into tailnet-only mode.

Deviations from the proposal below:
- Open tailnet connections drop when the proxy is turned on or off: the TUN (routes, DNS) and hev are rebuilt. The node
  keeps running, so reconnecting works at once.
- hev's config is the same in both modes (mapdns stays configured, unused in tailnet-only mode), instead of turning
  mapdns off.
- Tailnet-only mode ignores split tunnelling: all apps except ResProxy reach the tailnet.
- No new service mode parameter: `connectLocked` reads `wasConnected` / `tailnet.enabled`; `ACTION_CONNECT` turns the
  proxy on, `ACTION_RESUME` starts whatever is on (no-op while something runs: toggling the tailnet with the proxy up
  doesn't reconnect the proxy, verified on the Samsung), Always-on brings back whatever is on and turns the proxy on only
  when nothing is.
- Not tested: strict Private DNS (a specific provider in Android settings), which may bypass the VPN's DNS server and
  break tailnet names with the proxy off (the Tailscale app has the same issue); lockdown mode.

## Goal

The proxy tunnel (home power button) and the tailnet (Settings → Tailnet → Join) turn on and off independently:

| Proxy | Tailnet | What runs |
|---|---|---|
| off | off | nothing (service stopped) |
| on | off | today's tunnel |
| on | on | today's tunnel + tailnet |
| off | on | **new: tailnet-only VPN**, like the Tailscale app. Only tailnet addresses go into the TUN; everything else goes direct, without the proxy |

## Why they're tied today

- Android keeps background work alive only inside a foreground service. The VPN service is that service, so the node
  runs only while the service runs, and Disconnect stops the service.
- Inbound connections from peers are delivered to the TUN address `198.18.0.1:port`. That's what limits sharing to
  services that listen on all interfaces. No TUN, no target.
- Apps on the phone reach `100.x` peers only because their packets enter the TUN; tsnet has no TUN of its own.

The login itself survives Disconnect (the node key is kept in `files/tailnet/state.enc`); only the running node stops.

## Design

The service runs whenever proxy **or** tailnet is wanted. What the TUN carries depends on which:

- **Proxy on:** unchanged (all traffic, or the selected apps, into the TUN; tailnet targets dispatched first in the relay).
- **Proxy off, tailnet on:** a TUN with routes `100.64.0.0/10` and `fd7a:115c:a1e0::/48` only, same TUN address
  `198.18.0.1`, hev and `LocalSocksServer` with `upstream = null`. The relay already dispatches tailnet targets before
  the gate and refuses the rest; nothing else reaches it because nothing else is routed. No proxy, no exit-IP check, no
  gate, no health loop / repairs, no WireGuard chain.
- Switching proxy on/off while the tailnet runs: the node keeps running; the TUN is rebuilt with the new routes, the
  old fd held until the new one is established (invariant 1). hev keeps running (fake-IP gotcha), only the relay's
  `upstream` changes.

### MagicDNS in tailnet-only mode (decision needed)

1. **IPs only (first step):** the tailnet-only TUN sets no DNS server; apps resolve through the normal network. `100.x`
   addresses work, MagicDNS names (`laptop`, `laptop.tail1234.ts.net`) don't.
2. **Names too (follow-up):** TUN DNS `100.100.100.100` (inside the routed range), hev's mapdns off in this mode, and the
   relay answers UDP ASSOCIATE to port 53 itself: peer names from the node's status, everything else forwarded directly
   to the network's resolver. Needs UDP handling in the no-upstream relay.

Not an option: pointing the tailnet-only TUN's DNS at hev's fake-IP mapdns. Every named connection would come back as a
198.19.x address and go through the userspace relay without a proxy, the opposite of "proxy off", and UDP to named
hosts would break.

## Changes

- **State:** `wasConnected` stays "the user wants the proxy". The service is wanted when
  `wasConnected || tailnet.enabled`. Disconnect clears `wasConnected` only; the service stops if the tailnet is off,
  otherwise it switches to tailnet-only. The Tailnet toggle starts the service if needed and stops it if the proxy is off.
- **Keep-alive (invariant 5):** `supervise()`, `DeadManAlarm`, `KeepAliveJob`, boot, `USER_PRESENT` and opening the app
  use "proxy or tailnet wanted". A tailnet-only service is restarted like the tunnel.
- **TunnelState:** a phase for tailnet-only (e.g. `TAILNET_ONLY`), so Home, the notification and the quick-settings
  tile don't say Disconnected while a foreground VPN runs. Home: power button off, with a line "Tailnet on — proxy off".
  Notification: "Tailnet connected · proxy off" with a "Turn off tailnet" action.
- **Service:** `connect()` takes the mode. Tailnet-only skips `startChain` / `startUpstream` / `launchCheck` /
  `healthLoop`; relay refusals don't count as failures there. `TailnetController` binds on service start whenever the
  tailnet is enabled, no longer on `wasConnected`; it's unbound when the service stops.
- **Lockdown:** with "Block connections without VPN", Android drops everything not routed into the TUN, so tailnet-only
  mode means no internet (the Tailscale app has the same limit). Detect `lockdown()` and say so on the Tailnet page and in
  the notification.
- **Crash guard:** unchanged, but tested for a node started without the proxy.
- **Settings:** no new fields (the state comes from existing `wasConnected` and `tailnet.enabled`), so no format change.
- **Docs:** AGENTS.md traffic path, tailnet paragraph, invariants 3 and 5; README.

## Tests

- Unit: route set per mode (`Routes`), service-wanted logic, relay with `upstream = null` dispatches tailnet and refuses
  the rest without counting failures.
- Emulator, against `cmd/tailnettest`: all four states and every transition between them; a peer reachable by IP in
  tailnet-only mode while websites show the real IP (no proxy); inbound to an all-interfaces listener in both modes; SSH
  session to a peer survives turning the proxy on and off; `kill -9` in tailnet-only mode comes back via the alarm;
  lockdown warning.
