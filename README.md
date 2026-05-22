# Lighting Control

Hubitat app for controlling dimmers and multi-toggle switches from motion or
presence sources.

## Behavior

- Create one configuration set per room or area.
- Give each configuration set its own name.
- In each configuration set, select one motion sensor, one presence sensor, or
  both.
- In each configuration set, select one or more dimmers, single-toggle
  switches, and/or multi-toggle switches.
- Choose when daylight starts and when nighttime starts. Each boundary can be
  a fixed hour, sunrise, or sunset.
- Dimmers are set to the configured day or night level when the app turns them
  on.
- Single-toggle switches are turned on and off directly, with no dwell delay or
  special sequence.
- Multi-toggle switches are driven directly from the current day/night
  boundary: daytime uses a single on command, and nighttime uses an on/off/on
  command sequence. If the switch was just turned off, the app waits through
  the configured mode-switch dwell before starting either sequence so a recent
  off event cannot accidentally carry over the previous mode. When a
  multi-toggle switch is already on, the app leaves its current mode unchanged
  so physical switch toggles can be used for manual mode changes.
- Lights remain on while motion or presence is active. When motion or presence
  is absent, the app leaves them on for the configured off delay, then turns
  them off.
- The app subscribes to the controlled devices' switch states and corrects them
  back to the expected state.

## Hubitat

Target platform tested against Hubitat Elevation C-8 Pro platform `2.4.2.157`.
